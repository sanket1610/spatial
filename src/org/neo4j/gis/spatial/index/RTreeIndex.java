/*
 * Copyright (c) 2010
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.index;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.neo4j.gis.spatial.Constants;
import org.neo4j.gis.spatial.SpatialIndexWriter;
import org.neo4j.gis.spatial.SpatialRelationshipTypes;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;


/**
 * @author Davide Savazzi
 */
public class RTreeIndex extends AbstractSpatialIndex implements SpatialIndexWriter, Constants {

	// Constructor
	
	public RTreeIndex(GraphDatabaseService database, long layerNodeId) {
		this(database, layerNodeId, 100, 40);
	}
	
	public RTreeIndex(GraphDatabaseService database, long layerNodeId, int maxNodeReferences, int minNodeReferences) {
		this.database = database;
		this.layerNodeId = layerNodeId;
		this.maxNodeReferences = maxNodeReferences;
		this.minNodeReferences = minNodeReferences;
		
		initIndexMetadata();
	}
	
	
	// Public methods
	
	public void add(Node geomRootNode) {
		// initialize the search with root
		Node parent = getIndexRoot();
		
		// choose a path down to a leaf
		while (!nodeIsLeaf(parent)) {
			parent = chooseSubTree(parent, geomRootNode);
		}
		
		if (countChildren(parent, SpatialRelationshipTypes.RTREE_REFERENCE) == maxNodeReferences) {
			insertInLeaf(parent, geomRootNode);
			splitAndAdjustPathBoundingBox(parent);
		} else {
			if (insertInLeaf(parent, geomRootNode)) {
				// bbox enlargement needed
				adjustPathBoundingBox(parent);							
			}
		}
	}
	
	public Envelope getBoundingBox() {
		Node root = getIndexRoot();
		return getEnvelope(root);
	}
	
	public List<Node> search(Envelope searchEnvelope) {
		Node root = getIndexRoot();
		
		GeometryFactory geomFactory = new GeometryFactory();
		Geometry searchEnvelopeGeom = geomFactory.toGeometry(searchEnvelope);
		
		List<Node> result = search(root, searchEnvelope, searchEnvelopeGeom, geomFactory);
		if (result == null) result = new ArrayList<Node>(0);
		return result;
	}

	
	// Private methods

	private List<Node> search(Node node, Envelope searchEnvelope, Geometry searchEnvelopeGeom, GeometryFactory geomFactory) {
		if (!searchEnvelope.intersects(getEnvelope(node))) return null;
		
		List<Node> result = null;
		
		if (node.hasRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			// Node is not a leaf
			for (Relationship rel : node.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
				Node child = rel.getEndNode();
				// collect children results
				List<Node> childResult = search(child, searchEnvelope, searchEnvelopeGeom, geomFactory);
				if (childResult != null) {
					if (result == null) {
						result = new ArrayList<Node>(childResult);
					} else {
						result.addAll(childResult);
					}
				}
			}
		} else if (node.hasRelationship(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
			// Node is a leaf
			for (Relationship rel : node.getRelationships(SpatialRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
				Node geomRootNode = rel.getEndNode();
				
				Envelope geomEnvelope = getEnvelope(geomRootNode);
				if (searchEnvelope.contains(geomEnvelope)) {
					if (result == null) {
						result = new ArrayList<Node>();
					}					
					result.add(geomRootNode);
				} else if (searchEnvelope.intersects(geomEnvelope)) {
					Geometry geom = getGeometry(geomFactory, geomRootNode);
					if (searchEnvelopeGeom.intersects(geom)) {
						if (result == null) {
							result = new ArrayList<Node>();
						}
						result.add(geomRootNode);
					}
				}
			}
		}
		
		return result;
	}	
	
	private void initIndexMetadata() {
		Node layerNode = database.getNodeById(layerNodeId);
		if (layerNode.hasRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING)) {
			// metadata already present
			Node metadataNode = layerNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_METADATA, Direction.OUTGOING).getEndNode();
			
			maxNodeReferences = (Integer) metadataNode.getProperty("maxNodeReferences");
			minNodeReferences = (Integer) metadataNode.getProperty("minNodeReferences");
		} else {
			// metadata initialization
			Node metadataNode = database.createNode();
			layerNode.createRelationshipTo(metadataNode, SpatialRelationshipTypes.RTREE_METADATA);
			
			metadataNode.setProperty("maxNodeReferences", maxNodeReferences);
			metadataNode.setProperty("minNodeReferences", minNodeReferences);
		}
	}

	private Node getIndexRoot() {
		Node layerNode = database.getNodeById(layerNodeId);
		if (layerNode.hasRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING)) {
			// index already present
			return layerNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).getEndNode();
		} else {
			// index initialization
			Node root = database.createNode();
			layerNode.createRelationshipTo(root, SpatialRelationshipTypes.RTREE_ROOT);
			return root;
		}
	}
	
	private boolean nodeIsLeaf(Node node) {
		return !node.hasRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
	}
	
	private Node chooseSubTree(Node parentIndexNode, Node geomRootNode) {
		// children that can contain the new geometry
		List<Node> indexNodes = new ArrayList<Node>();
		
		// pick the child that contains the new geometry bounding box		
		Iterable<Relationship> relationships = parentIndexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);		
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			if (getEnvelope(indexNode).contains(getEnvelope(geomRootNode))) {
				indexNodes.add(indexNode);
			}
		}

		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		}
		
		// pick the child that needs the minimum enlargement to include the new geometry
		double minimumEnlargement = Double.POSITIVE_INFINITY;
		relationships = parentIndexNode.getRelationships(SpatialRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			double enlargementNeeded = getAreaEnlargement(indexNode, geomRootNode);

			if (enlargementNeeded < minimumEnlargement) {
				indexNodes.clear();
				indexNodes.add(indexNode);
				minimumEnlargement = enlargementNeeded;
			} else if (enlargementNeeded == minimumEnlargement) {
				indexNodes.add(indexNode);				
			}
		}
		
		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		} else {
			// this shouldn't happen
			throw new SpatialIndexException("No IndexNode found for new geometry");
		}
	}

    private double getAreaEnlargement(Node indexNode, Node geomRootNode) {
    	Envelope before = getEnvelope(indexNode);
    	
    	Envelope after = getEnvelope(geomRootNode);
    	after.expandToInclude(before);
    	
    	return getArea(after) - getArea(before);
    }
	
	private Node chooseIndexNodeWithSmallestArea(List<Node> indexNodes) {
		Node result = null;
		double smallestArea = -1;

		for (Node indexNode : indexNodes) {
			double area = getArea(indexNode);
			if (result == null || area < smallestArea) {
				result = indexNode;
				smallestArea = area;
			}
		}
		
		return result;
	}

	private int countChildren(Node indexNode, RelationshipType relationshipType) {
		int counter = 0;
		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			iterator.next();
			counter++;
		}
		return counter;
	}
	
	/**
	 * @return is enlargement needed?
	 */
	private boolean insertInLeaf(Node indexNode, Node geomRootNode) {
		return addChild(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE, geomRootNode);
	}

	private void splitAndAdjustPathBoundingBox(Node indexNode) {
		// create a new node and distribute the entries
		Node newIndexNode = quadraticSplit(indexNode);
		Node parent = getParent(indexNode);
		if (parent == null) {
			// if indexNode is the root
			createNewRoot(indexNode, newIndexNode);
		} else {
			adjustParentBoundingBox(parent, indexNode);
			
			addChild(parent, SpatialRelationshipTypes.RTREE_CHILD, newIndexNode);

			if (countChildren(parent, SpatialRelationshipTypes.RTREE_CHILD) > maxNodeReferences) {
				splitAndAdjustPathBoundingBox(parent);
			} else {
				adjustPathBoundingBox(parent);
			}
		}
	}

	private Node quadraticSplit(Node indexNode) {
		if (nodeIsLeaf(indexNode)) return quadraticSplit(indexNode, SpatialRelationshipTypes.RTREE_REFERENCE);
		else return quadraticSplit(indexNode, SpatialRelationshipTypes.RTREE_CHILD);
	}

	private Node quadraticSplit(Node indexNode, RelationshipType relationshipType) {
 		List<Node> entries = new ArrayList<Node>();
		
		Iterable<Relationship> relationships = indexNode.getRelationships(relationshipType, Direction.OUTGOING);
		for (Relationship relationship : relationships) {
			entries.add(relationship.getEndNode());
			relationship.delete();
		}

		// pick two seed entries such that the dead space is maximal
		Node seed1 = null;
		Node seed2 = null;
		double worst = Double.NEGATIVE_INFINITY;
		for (Node e : entries) {
			Envelope eEnvelope = getEnvelope(e);
			for (Node e1 : entries) {
				Envelope e1Envelope = getEnvelope(e1);
				double deadSpace = getArea(getEnvelope(eEnvelope, e1Envelope)) - getArea(eEnvelope) - getArea(e1Envelope);
				if (deadSpace > worst) {
					worst = deadSpace;
					seed1 = e;
					seed2 = e1;
				}
			}
		}
		
		List<Node> group1 = new ArrayList<Node>();
		group1.add(seed1);
		Envelope group1envelope = getEnvelope(seed1);
		
		List<Node> group2 = new ArrayList<Node>();
		group2.add(seed2);
		Envelope group2envelope = getEnvelope(seed2);
		
		entries.remove(seed1);
		entries.remove(seed2);
		while (entries.size() > 0) {
			// compute the cost of inserting each entry
			List<Node> bestGroup = null;
			Envelope bestGroupEnvelope = null;
			Node bestEntry = null;
			double expansionMin = Double.POSITIVE_INFINITY;
			for (Node e : entries) {
				double expansion1 = getArea(getEnvelope(getEnvelope(e), group1envelope)) - getArea(group1envelope);
				double expansion2 = getArea(getEnvelope(getEnvelope(e), group2envelope)) - getArea(group2envelope);
						
				if (expansion1 < expansion2 && expansion1 < expansionMin) {
					bestGroup = group1;
					bestGroupEnvelope = group1envelope;
					bestEntry = e;
					expansionMin = expansion1;
				} else if (expansion2 < expansion1 && expansion2 < expansionMin) {
					bestGroup = group2;
					bestGroupEnvelope = group2envelope;					
					bestEntry = e;
					expansionMin = expansion2;					
				} else if (expansion1 == expansion2 && expansion1 < expansionMin) {
					// in case of equality choose the group with the smallest area
					if (getArea(group1envelope) < getArea(group2envelope)) {
						bestGroup = group1;
						bestGroupEnvelope = group1envelope; 
					} else {
						bestGroup = group2;
						bestGroupEnvelope = group2envelope; 
					}
					bestEntry = e;
					expansionMin = expansion1;					
				}
			}
			
			// insert the best candidate entry in the best group
			bestGroup.add(bestEntry);
			bestGroupEnvelope.expandToInclude(getEnvelope(bestEntry));

			entries.remove(bestEntry);
			
			// each group must contain at least minNodeReferences entries.
			// if the group size added to the number of remaining entries is equal to minNodeReferences
			// just add them to the group
			
			if (group1.size() + entries.size() == minNodeReferences) {
				group1.addAll(entries);
				entries.clear();
			}
			
			if (group2.size() + entries.size() == minNodeReferences) {
				group2.addAll(entries);
				entries.clear();
			}
		}
		
		for (Node node : group1) {
			addChild(indexNode, relationshipType, node);
		}

		Node newIndexNode = database.createNode();
		for (Node node: group2) {
			addChild(newIndexNode, relationshipType, node);
		}
		
		return newIndexNode;
	}

	private void createNewRoot(Node oldRoot, Node newIndexNode) {
		Node newRoot = database.createNode();
		addChild(newRoot, SpatialRelationshipTypes.RTREE_CHILD, oldRoot);
		addChild(newRoot, SpatialRelationshipTypes.RTREE_CHILD, newIndexNode);
		
		Node layerNode = database.getNodeById(layerNodeId);
		layerNode.getSingleRelationship(SpatialRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).delete();
		layerNode.createRelationshipTo(newRoot, SpatialRelationshipTypes.RTREE_ROOT);
	}
	
	private boolean addChild(Node parent, RelationshipType type, Node newChild) {
		parent.createRelationshipTo(newChild, type);
		return adjustParentBoundingBox(parent, newChild);
	}
	
	private Envelope getEnvelope(Envelope e, Envelope e1) {
		Envelope result = new Envelope(e);
		result.expandToInclude(e1);
		return result;
	}
	
	private void adjustPathBoundingBox(Node indexNode) {
		Node parent = getParent(indexNode);
		if (parent != null) {
			if (adjustParentBoundingBox(parent, indexNode)) {
				// entry has been modified: adjust the path for the parent
				adjustPathBoundingBox(parent);
			}
		}
	}

	private boolean adjustParentBoundingBox(Node parent, Node child) {
		if (!parent.hasProperty(PROP_BBOX)) {
			double[] childBBox = (double[]) child.getProperty(PROP_BBOX);
			parent.setProperty(PROP_BBOX, new double[] { childBBox[0], childBBox[1], childBBox[2], childBBox[3] });
			return true;
		}
		
		double[] parentBBox = (double[]) parent.getProperty(PROP_BBOX);
		double[] childBBox = (double[]) child.getProperty(PROP_BBOX);
		
		boolean valueChanged = setMin(parentBBox, childBBox, 0);
		valueChanged = setMin(parentBBox, childBBox, 1) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 2) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 3) || valueChanged;
		
		if (valueChanged) parent.setProperty(PROP_BBOX, parentBBox);
		
		return valueChanged;
	}

	private boolean setMin(double[] parent, double[] child, int index) {
		if (parent[index] > child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}
	
	private boolean setMax(double[] parent, double[] child, int index) {
		if (parent[index] < child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}
	
	private Node getParent(Node node) {
		Relationship relationship = node.getSingleRelationship(SpatialRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
		if (relationship == null) return null;
		else return relationship.getStartNode();
	}	
	
	private double getArea(Node node) {
		return getArea(getEnvelope(node));
	}

	private double getArea(Envelope e) {
		return e.getWidth() * e.getHeight();
	}
	
	
	// Attributes
	
	private GraphDatabaseService database;
	private long layerNodeId;
	private int maxNodeReferences;
	private int minNodeReferences;
}