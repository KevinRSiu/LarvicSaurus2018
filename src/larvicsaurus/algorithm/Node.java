package larvicsaurus.algorithm;

import java.util.Collection;

import adf.agent.info.WorldInfo;
import rescuecore2.worldmodel.EntityID;

public class Node {
	EntityID id;
	EntityID parent;
	double cost;
	double heuristic;
	
	public Node(Node from, EntityID id, WorldInfo wi, Collection<EntityID> targets){
		this.id = id;
		if(from == null){
			this.cost = 0;
		}else{
			this.parent = from.getID();
			this.cost = from.getCost() + wi.getDistance(from.getID(), id);
		}
		
		this.heuristic = wi.getDistance(id, targets.toArray(new EntityID[targets.size()])[0]);
	}
	
	public EntityID getID(){
		return id;
	}
	
	public double getCost(){
		return cost;
	}
	
	public double estimate(){
		return cost + heuristic;
	}
	
	public EntityID getParent(){
		return this.parent;
	}
}
