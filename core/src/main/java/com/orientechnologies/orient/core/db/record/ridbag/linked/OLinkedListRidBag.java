/*
 * Copyright 2018 OrientDB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.db.record.ridbag.linked;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.OMultiValueChangeEvent;
import com.orientechnologies.orient.core.db.record.OMultiValueChangeListener;
import com.orientechnologies.orient.core.db.record.ridbag.ORidBagDelegate;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.BytesContainer;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.HelperClasses;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.OVarIntSerializer;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.cluster.OPaginatedCluster;
import com.orientechnologies.orient.core.storage.cluster.linkedridbags.OFastRidBagPaginatedCluster;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.Change;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.UUID;

/**
 *
 * @author marko
 */
public class OLinkedListRidBag implements ORidBagDelegate{           
  
  private OIdentifiable ridbagRid;  
  private Map<OIdentifiable, ORidbagNode> indexedRidsNodes;
  private OFastRidBagPaginatedCluster cluster = null;
  private int size = 0;
  private final List<ORidbagNode> ridbagNodes = new LinkedList<>();  
  //this is internal ridbag list of free nodes already allocated. Differs from cluster free space
  //free nodes list should never conatins tailNode
  private final Queue<ORidbagNode> freeNodes = new LinkedList<>();
  
  protected static final int MAX_RIDBAG_NODE_SIZE = 600;
  private static final int ADDITIONAL_ALLOCATION_SIZE = 20;  
  private static boolean hardRelaxPolicy = false;
  
  //if some node is made by merging, until its capacity is fullfillled it should be active node
  //active node never should be tail node
  private ORidbagNode activeNode = null;
  private ORidbagNode tailNode;
  
  private boolean autoConvertToRecord = true;
  private List<OMultiValueChangeListener<OIdentifiable, OIdentifiable>> changeListeners;
  private ORecord owner = null;    
  
  public OLinkedListRidBag(OFastRidBagPaginatedCluster cluster){    
    this.cluster = cluster;
  }
  
  @Override
  public void addAll(Collection<OIdentifiable> values) {    
    values.forEach(this::add);
  }

  @Override
  public void add(OIdentifiable value) {    
    if (value == null)
      throw new IllegalArgumentException("Impossible to add a null identifiable in a ridbag");
    
    if (tailNode == null){
      try{
        tailNode = createNodeOfSpecificSize(1, true);
        ridbagNodes.add(tailNode);
      }
      catch (IOException exc){
        OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
        throw new ODatabaseException(exc.getMessage());
      }
    }
    
    //check for megaMerge
    if (size > 0 && size % MAX_RIDBAG_NODE_SIZE == 0){
      nodesMegaMerge(hardRelaxPolicy);
    }
    
    //check if there is active node with free space
    if (activeNode != null){
      if (activeNode.currentIndex() >= activeNode.capacity()){
        activeNode = freeNodes.poll();        
      }      
    }
    
    if (activeNode == null){
      //handle add to tail
      boolean canFitInPage;
      try{
        canFitInPage = ifOneMoreFitsToPage(value);
      }
      catch (IOException exc){
        OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
        throw new ODatabaseException(exc.getMessage());
      }
      if (!canFitInPage){
        int tailSize = tailNode.currentIndex();
        int allocateSize = Math.min(tailSize * 2, tailSize + ADDITIONAL_ALLOCATION_SIZE);
        allocateSize = Math.min(allocateSize, MAX_RIDBAG_NODE_SIZE);
        
        int extraSlots = 1;
        if (allocateSize == MAX_RIDBAG_NODE_SIZE){
          extraSlots = 0;
        }
        //created merged node
        allocateSize += extraSlots;
        ORidbagNode ridBagNode;
        try{
          ridBagNode = getOrCreateNodeOfSpecificSize(allocateSize, true);
          ridbagNodes.add(tailNode);
        }
        catch (IOException exc){
          OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
          throw new ODatabaseException(exc.getMessage());
        }
        
        OIdentifiable[] mergedTail = mergeTail(extraSlots);
        if (extraSlots == 1){
          //here we are dealing with node size less than max node size
          mergedTail[mergedTail.length - 1] = value;
          activeNode = ridBagNode;
          activeNode.addAll(mergedTail);          
          relaxTail(hardRelaxPolicy);
          //no increment of tailSize bacuse no way that this node is tail node
        }
        else{
          //here we deal with node which size is equal to max node size          
          ridBagNode.addAll(mergedTail);          
          relaxTail(hardRelaxPolicy);
          //add new rid to tail
          tailNode.add(value);          
          ++tailSize;          
        }                
      }
      else{                
        tailNode.add(value);
      }
    }
    else{
      if (!activeNode.isLoaded()){
        activeNode.load();
      }
      activeNode.add(value);      
    }
    
    ++size;
    
    fireCollectionChangedEvent(
        new OMultiValueChangeEvent<>(OMultiValueChangeEvent.OChangeType.ADD, value, value, null, false));
    //TODO add it to index
  }
  
  /**
   * 
   */
  private void nodesMegaMerge(boolean hardRelax){
    OIdentifiable[] mergedRids = new OIdentifiable[MAX_RIDBAG_NODE_SIZE];
    int currentOffset = 0;
    Iterator<ORidbagNode> iter = ridbagNodes.iterator();
    while (iter.hasNext()){
      ORidbagNode node = iter.next();
      if (!node.isMaxSizeNodeFullNode()){
        if (!node.isLoaded()){
          node.load();
        }
        if (node.currentIndex() > 0){
          OIdentifiable[] nodeRids = node.getAllRids();
          System.arraycopy(nodeRids, 0, mergedRids, currentOffset, node.currentIndex());
          final boolean isTailNode = node.isTailNode();
          
          if (hardRelax){
            //release it in cluster
          }
          else{            
            node.reset();
            if (!isTailNode){
              iter.remove();
              freeNodes.add(node);
            }
          }
        }
      }
    }
    
    ORidbagNode megaNode;
    try{
      megaNode = getOrCreateNodeOfSpecificSize(mergedRids.length, true);
    }
    catch (IOException exc){
      OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
      throw new ODatabaseException(exc.getMessage());
    }
    
    megaNode.addAll(mergedRids);
    ridbagNodes.add(megaNode);
  }
  
  private OIdentifiable[] mergeTail(int extraSlots) {
    int tailSize = tailNode.currentIndex();    
    OIdentifiable[] tailRids = tailNode.getAllRids();
    OIdentifiable[] ret;
    if (extraSlots > 0){
      ret = new OIdentifiable[tailSize + extraSlots];
      System.arraycopy(tailRids, 0, ret, 0, tailRids.length);
    }
    else{
      ret = tailRids;
    }
    return ret;
  }
  
  private void relaxTail(boolean hardRelax){    
    if (hardRelax){
      //TODO mark in cluster that it is removed
    }
    else{
      tailNode.reset();
    }        
  }

  @Override
  public void remove(OIdentifiable value) {
    boolean removed = false;    
    if (indexedRidsNodes == null){
      ORidbagNode node = indexedRidsNodes.get(value);
      if (node != null){
        if (!node.isLoaded()){
          node.load();
        }
        boolean isTail = node.isTailNode();
        if (node.remove(value)){
          if (activeNode == null && !isTail){
            activeNode = node;
          }
          else{
            if (!isTail){
              ridbagNodes.remove(node);
              freeNodes.add(node);
            }
          }          
          removed = true;
        }
      }
    }
    else{
      //go through all
      for (ORidbagNode ridbagNode : ridbagNodes){
        if (!ridbagNode.isLoaded()){
          ridbagNode.load();
        }
        boolean isTail = ridbagNode.isTailNode();
        if (ridbagNode.remove(value)){
          if (activeNode == null && !isTail){
            activeNode = ridbagNode;
          }
          else{
            if (!isTail){
              ridbagNodes.remove(ridbagNode);
              freeNodes.add(ridbagNode);
            }
          }          
          removed = true;
          break;
        }
      }
    }
    if (removed){
      --size;
    }    
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public int getSerializedSize() {
    BytesContainer container = new BytesContainer();
    try{
      serializeInternal(container, true);
    }
    catch (IOException exc){
      OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
      throw new ODatabaseException(exc.getMessage());
    }
    
    return container.offset;
  }

  @Override
  public int getSerializedSize(byte[] stream, int offset) {
    BytesContainer container = new BytesContainer();
    try{
      serializeInternal(container, true);
    }
    catch (IOException exc){
      OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
      throw new ODatabaseException(exc.getMessage());
    }
    
    return container.offset;
  }

  private void serializeRidbagNodeMetadata(BytesContainer container, ORidbagNode node){
    int pos = container.alloc(1);
    OByteSerializer.INSTANCE.serializeNative(node.getNodeType(), container.bytes, pos);    
    OVarIntSerializer.write(container, node.getClusterPosition());
    OVarIntSerializer.write(container, node.currentIndex());
    OVarIntSerializer.write(container, node.capacity());
  }
  
  private ORidbagNode deserializeRidbagNodeMetaadata(BytesContainer container){
    byte type = OByteSerializer.INSTANCE.deserialize(container.bytes, container.offset++);
    long clusterPos = OVarIntSerializer.readAsLong(container);
    int currentIndex = OVarIntSerializer.readAsInteger(container);
    int capacity = OVarIntSerializer.readAsInteger(container);    
    ORidbagNode node;
    if (type == ORidBagArrayNode.RIDBAG_ARRAY_NODE_TYPE){      
      node = new ORidBagArrayNode(clusterPos, capacity, false);      
    }
    else{
      //list based ridbag node has to be loaded
      node = new ORidbagListNode(clusterPos, false);
      tailNode = node;
    }
    node.currentIndex = currentIndex;
    return node;
  }
  
  private void serializeInternal(BytesContainer container, boolean justRunThrough) throws IOException{
    //serialize currentSize
    OVarIntSerializer.write(container, size);
    
    //serialize active node
    if (activeNode != null){           
      OVarIntSerializer.write(container, activeNode.getClusterPosition());
    }
    else{
      OVarIntSerializer.write(container, -1l);
    }
    
    //serailize free nodes queue size
    OVarIntSerializer.write(container, freeNodes.size());
    
    //serialize free nodes queue
    for (ORidbagNode node : freeNodes){
      serializeRidbagNodeMetadata(container, node);
      if (!justRunThrough){
        serializeNodeData(node);
      }
    }
    
    //serialize size of associated ridbag nodes
    OVarIntSerializer.write(container, ridbagNodes.size());
    
    //serialize nodes associated with this ridbag    
    for (ORidbagNode node : ridbagNodes){
      serializeRidbagNodeMetadata(container, node);
      if (!justRunThrough){
        serializeNodeData(node);
      }
    }        
  }
  
  private void serializeNodeData(ORidbagNode node) throws IOException{
    byte[] serialized = node.serialize();
    OPhysicalPosition ppos = new OPhysicalPosition(node.getClusterPosition());
    OPaginatedCluster.RECORD_STATUS status = cluster.getRecordStatus(node.getClusterPosition());
    if (status == OPaginatedCluster.RECORD_STATUS.ALLOCATED || status == OPaginatedCluster.RECORD_STATUS.REMOVED){
      cluster.createRecord(serialized, node.getVersion(), ORidbagNode.RECORD_TYPE, ppos);
    }
    else if (status == OPaginatedCluster.RECORD_STATUS.PRESENT){
      cluster.updateRecord(ppos.clusterPosition, serialized, node.getVersion(), ORidbagNode.RECORD_TYPE);
    }
  }
  
  @Override
  public int serialize(byte[] stream, int offset, UUID ownerUuid) {
    BytesContainer container = new BytesContainer(stream, offset);
    try{
      serializeInternal(container, false);
    }
    catch (IOException exc){
      OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
    }
    return container.offset;
  }

  @Override
  public int deserialize(byte[] stream, int offset) {
    BytesContainer container = new BytesContainer(stream, offset);
    //deserialize size
    size = OVarIntSerializer.readAsInteger(container);
    
    //deserialize activeNode cluster position    
    long activeNodeClusterPosition = OVarIntSerializer.readAsLong(container);    
    
    //deserialize free nodes queue size
    int nodesSize = OVarIntSerializer.readAsInteger(container);
    
    //deserialize free nodes queue rids    
    for (int i = 0; i < nodesSize; i++){
      ORidbagNode node = deserializeRidbagNodeMetaadata(container);
      freeNodes.add(node);
    }
    
    //deserialize associated nodes size
    nodesSize = OVarIntSerializer.readAsInteger(container);
    for (int i = 0; i < nodesSize; i++){
      ORidbagNode node = deserializeRidbagNodeMetaadata(container);
      //setup active node
      if (activeNodeClusterPosition != -1 && node.getClusterPosition() == activeNodeClusterPosition){
        activeNode = node;
      }
      
      ridbagNodes.add(node);
    }
    
    return container.offset;
  }

  private void deleteRidbagNodeData(ORidbagNode node) throws IOException{
    OPhysicalPosition nodesRidPos = new OPhysicalPosition(node.getClusterPosition());
    OPhysicalPosition pos = cluster.getPhysicalPosition(nodesRidPos);
    cluster.deleteRecord(pos.clusterPosition);
  }
  
  @Override
  public void requestDelete() {
    for (ORidbagNode node : freeNodes){        
      try{
        deleteRidbagNodeData(node);
      }
      catch (IOException exc){
        OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
      }
    }
    
    for (ORidbagNode node : ridbagNodes){
      try{
        deleteRidbagNodeData(node);
      }
      catch (IOException exc){
        OLogManager.instance().errorStorage(this, exc.getMessage(), exc, (Object[])null);
      }
    }
    
    freeNodes.clear();
    ridbagNodes.clear();
  }

  @Override
  public boolean contains(OIdentifiable value) {    
    if (indexedRidsNodes == null){
      ORidbagNode node = indexedRidsNodes.get(value);
      if (node != null){
        if (!node.isLoaded()){
          node.load();
        }
        return node.contains(value);
      }
    }
    else{    
      //go through all
      for (ORidbagNode ridbagNode : ridbagNodes){
        if (ridbagNode.isLoaded()){
          ridbagNode.load();
        }
        if (ridbagNode.contains(value)){
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void setOwner(ORecord owner) {
    if (owner != null && this.owner != null && !this.owner.equals(owner)) {
      throw new IllegalStateException("This data structure is owned by document " + owner
          + " if you want to use it in other document create new rid bag instance and copy content of current one.");
    }
    if (this.owner != null) {
      Iterator<ORidbagNode> iter = ridbagNodes.iterator();
      while (iter.hasNext()){
        ORidbagNode ridbagNode = iter.next();
        if (!ridbagNode.isLoaded()){
          ridbagNode.load();
        }
        for (int i = 0; i < ridbagNode.currentIndex(); i++){          
          ORecordInternal.unTrack(this.owner, ridbagNode.getAt(i));
        }
      }      
    }

    this.owner = owner;
    
    if (this.owner != null) {
      Iterator<ORidbagNode> iter = ridbagNodes.iterator();
      while (iter.hasNext()){
        ORidbagNode ridbagNode = iter.next();
        //no need to check if nodes are loaded, they are loaded in loop above
        for (int i = 0; i < ridbagNode.currentIndex(); i++){          
          ORecordInternal.track(this.owner, ridbagNode.getAt(i));
        }
      }      
    }
  }

  @Override
  public ORecord getOwner() {
    return owner;
  }

  @Override
  public List<OMultiValueChangeListener<OIdentifiable, OIdentifiable>> getChangeListeners() {
    if (changeListeners == null){
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(changeListeners);
  }

  @Override
  public NavigableMap<OIdentifiable, Change> getChanges() {
    return null;
  }

  @Override
  public void setSize(int size) {
    this.size = size;
  }

  @Override
  public Iterator<OIdentifiable> iterator() {
    return new OLinkedListRidBagIterator(this);
  }

  @Override
  public Iterator<OIdentifiable> rawIterator() {
    return new OLinkedListRidBagIterator(this);
  }

  @Override
  public void convertLinks2Records() {
    Iterator<ORidbagNode> iter = ridbagNodes.iterator();
    while (iter.hasNext()){
      ORidbagNode ridbagNode = iter.next();
      if (!ridbagNode.isLoaded()){
        ridbagNode.load();
      }
      for (int i = 0; i < ridbagNode.currentIndex(); i++){
        OIdentifiable id = ridbagNode.getAt(i);
        if (id.getRecord() != null){
          ridbagNode.setAt(id.getRecord(), i);
        }
      }
    }
  }

  @Override
  public boolean convertRecords2Links() {
    Iterator<ORidbagNode> iter = ridbagNodes.iterator();
    while (iter.hasNext()){
      ORidbagNode ridbagNode = iter.next();
      if (!ridbagNode.isLoaded()){
        ridbagNode.load();
      }
      for (int i = 0; i < ridbagNode.currentIndex(); i++){
        OIdentifiable id = ridbagNode.getAt(i);
        if (id instanceof ORecord){
          ORecord rec = (ORecord)id;
          ridbagNode.setAt(rec.getIdentity(), i);
        }
        else{
          return false;
        }
      }
    }        

    return true;
  }

  @Override
  public boolean isAutoConvertToRecord() {
    return autoConvertToRecord;
  }

  @Override
  public void setAutoConvertToRecord(boolean convertToRecord) {
    autoConvertToRecord = convertToRecord;
  }

  @Override
  public boolean detach() {
    return convertRecords2Links();
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public void addChangeListener(OMultiValueChangeListener<OIdentifiable, OIdentifiable> changeListener) {
    if (changeListeners == null){
      changeListeners = new LinkedList<>();
    }
    changeListeners.add(changeListener);
  }

  @Override
  public void removeRecordChangeListener(OMultiValueChangeListener<OIdentifiable, OIdentifiable> changeListener) {
    if (changeListeners != null){
      changeListeners.remove(changeListener);
    }
  }

  @Override
  public Object returnOriginalState(List<OMultiValueChangeEvent<OIdentifiable, OIdentifiable>> changeEvents) {
    final OLinkedListRidBag reverted = new OLinkedListRidBag(cluster);
    Iterator<ORidbagNode> iter = ridbagNodes.iterator();
    while (iter.hasNext()){
      ORidbagNode ridbagNode = iter.next();
      if (!ridbagNode.isLoaded()){
        ridbagNode.load();
      }
      for (int i = 0; i < ridbagNode.currentIndex(); i++){
        OIdentifiable id = ridbagNode.getAt(i);
        reverted.add(id);
      }
    }

    final ListIterator<OMultiValueChangeEvent<OIdentifiable, OIdentifiable>> listIterator = changeEvents
        .listIterator(changeEvents.size());

    while (listIterator.hasPrevious()) {
      final OMultiValueChangeEvent<OIdentifiable, OIdentifiable> event = listIterator.previous();
      switch (event.getChangeType()) {
      case ADD:
        reverted.remove(event.getKey());
        break;
      case REMOVE:
        reverted.add(event.getOldValue());
        break;
      default:
        throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }

    return reverted;
  }

  @Override
  public void fireCollectionChangedEvent(OMultiValueChangeEvent<OIdentifiable, OIdentifiable> event) {
    if (changeListeners != null) {
      for (final OMultiValueChangeListener<OIdentifiable, OIdentifiable> changeListener : changeListeners) {
        if (changeListener != null){
          changeListener.onAfterRecordChanged(event);
        }
      }
    }
  }

  @Override
  public Class<?> getGenericClass() {
    return OIdentifiable.class;
  }

  @Override
  public void replace(OMultiValueChangeEvent<Object, Object> event, Object newValue) {
    //do nothing
  }

  public OPaginatedCluster getCluster() {
    return cluster;
  }

  public void setCluster(OFastRidBagPaginatedCluster cluster) {
    this.cluster = cluster;
  }
  
  ORidbagNode getAtIndex(int index){
    if (index < 0 || index >= ridbagNodes.size()){
      return null;
    }
    return ridbagNodes.get(index);
  }
  
  /**
   * search for free node of specific size, and create new one is none can recycle
   * @param numberOfRids
   * @return 
   */
  private ORidbagNode getOrCreateNodeOfSpecificSize(int numberOfRids, boolean considerNodeLoaded) throws IOException{
    Iterator<ORidbagNode> iter = freeNodes.iterator();
    ORidbagNode ret = null;
    while (iter.hasNext() && ret == null){
      ORidbagNode freeNode  = iter.next();      
      if (freeNode.getFreeSpace() >= numberOfRids){
        ret = freeNode;
        if (!ret.isLoaded()){
          ret.load();
        }
        iter.remove();
      }
    }
    
    if (ret == null){
      ret = createNodeOfSpecificSize(numberOfRids, considerNodeLoaded);
    }
    
    return ret;
  }
  
  private ORidbagNode createNodeOfSpecificSize(int numberOfRids, boolean considerNodeLoaded) throws IOException{
    OPhysicalPosition newNodePhysicalPosition = preallocateRid();
    ORidbagNode ret;
    if (numberOfRids > 1){
      ret = new ORidBagArrayNode(newNodePhysicalPosition.clusterPosition, numberOfRids, considerNodeLoaded);
    }
    else{
      ret = new ORidbagListNode(newNodePhysicalPosition.clusterPosition, considerNodeLoaded);
    }
    
    return ret;
  }
  
  private OPhysicalPosition preallocateRid() throws IOException{
    return cluster.allocatePosition(ORidbagNode.RECORD_TYPE);    
  }
  
  private boolean ifOneMoreFitsToPage(OIdentifiable id) throws IOException{    
    BytesContainer container = new BytesContainer();
    HelperClasses.writeLinkOptimized(container, id);
    return cluster.checkIfNewContentFitsInPage(tailNode.getClusterPosition(), container.offset);    
  }
    
}