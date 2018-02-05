package larvicsaurus.algorithm;

import adf.agent.communication.MessageManager;
import adf.agent.develop.DevelopData;
import adf.agent.info.AgentInfo;
import adf.agent.info.ScenarioInfo;
import adf.agent.info.WorldInfo;
import adf.agent.module.ModuleManager;
import adf.agent.precompute.PrecomputeData;
import adf.component.module.algorithm.PathPlanning;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.Area;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class AStarPathPlanningNewB extends PathPlanning {

    private Map<EntityID, Set<EntityID>> graph;

    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;

    public AStarPathPlanningNewB(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.init();
    }

    private void init() {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        for (Entity next : this.worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.get(next.getID()).addAll(areaNeighbours);
            }
        }
        this.graph = neighbours;
    }

    @Override
    public List<EntityID> getResult() {
        return this.result;
    }

    @Override
    public PathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public PathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = targets;
        return this;
    }

    @Override
    public PathPlanning updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }

    @Override
    public PathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        return this;
    }

    @Override
    public PathPlanning calc() {
        List<EntityID> open = new LinkedList<>();
        List<EntityID> close = new LinkedList<>();
        Map<EntityID, NodeNewB> nodeMap = new HashMap<>();
        open.add(this.from);
        nodeMap.put(this.from, new NodeNewB(null, this.from, this.worldInfo, targets));
        close.clear();
        
        while(true){
        	if(open.size() < 0){
        		this.result = null;
        		return this;
        	}
        	NodeNewB n = null;
        	for(EntityID id : open){
        		NodeNewB node = nodeMap.get(id);
        		if(n == null){
        			n = node;
        		}else if(node.estimate() < n.estimate()){
        			n = node;
        		}
        	}
        	if(targets.contains(n.getID())){
        		List<EntityID> path = new LinkedList<>();
        		while(n != null){
        			path.add(0, n.getID());
        			n = nodeMap.get(n.getParent());
        		}
        		this.result = path;
        		return this;
        	}
        	open.remove(n.getID());
        	close.add(n.getID());
        	
        	Collection<EntityID> neighbours = this.graph.get(n.getID());
        	for(EntityID neighbour : neighbours){
        		NodeNewB m = new NodeNewB(n, neighbour, this.worldInfo, targets);
        		if(!open.contains(neighbour) && !close.contains(neighbour)){
        			open.add(m.getID());
        			nodeMap.put(neighbour, m);
        		}else if(open.contains(neighbour) && m.estimate() < nodeMap.get(neighbour).estimate()){
        			nodeMap.put(neighbour, m);
        		}else if(!close.contains(neighbour) && m.estimate() < nodeMap.get(neighbour).estimate()){
        			nodeMap.put(neighbour, m);
        		}
        	}
        }
    }
       
    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }
}

