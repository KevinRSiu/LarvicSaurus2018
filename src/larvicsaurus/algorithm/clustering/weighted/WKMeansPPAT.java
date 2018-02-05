package larvicsaurus.algorithm.clustering.weighted;

import adf.agent.communication.MessageManager;
import adf.agent.develop.DevelopData;
import adf.agent.info.AgentInfo;
import adf.agent.info.ScenarioInfo;
import adf.agent.info.WorldInfo;
import adf.agent.module.ModuleManager;
import adf.agent.precompute.PrecomputeData;
import adf.component.module.algorithm.Clustering;
import adf.component.module.algorithm.StaticClustering;
import rescuecore2.misc.Pair;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;


import java.util.*;

//import static java.util.Comparator.comparing;
//import static java.util.Comparator.reverseOrder;

public class WKMeansPPAT extends StaticClustering {
    private static final String KEY_CLUSTER_SIZE = "sample.clustering.size";
    private static final String KEY_CLUSTER_CENTER = "sample.clustering.centers";
    private static final String KEY_CLUSTER_ENTITY = "sample.clustering.entities.";
    private static final String KEY_ASSIGN_AGENT = "sample.clustering.assign";

    private int repeatPrecompute;
    private int repeatPreparate;

    private Collection<StandardEntity> entities;

    private List<StandardEntity> centerList;
    private List<EntityID> centerIDs;
    private Map<Integer, List<StandardEntity>> clusterEntitiesList;
    private List<List<EntityID>> clusterEntityIDsList;

    private int clusterSize;

    private boolean assignAgentsFlag;

    private Map<EntityID, Set<EntityID>> shortestPathGraph;
    
    Double LOW = 0.0;
    Double MEDIUM_LOW = 1.25;
    //Double MEDIUM = 1.0;
    Double MEDIUM_HIGH = 0.75;
    Double HIGH = 0.5;

    public WKMeansPPAT(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.repeatPrecompute = developData.getInteger("sample.module.SampleKMeans.repeatPrecompute", 7);
        this.repeatPreparate = developData.getInteger("sample.module.SampleKMeans.repeatPreparate", 30);
        this.clusterSize = developData.getInteger("sample.module.SampleKMeans.clusterSize", 10);
        this.assignAgentsFlag = developData.getBoolean("sample.module.SampleKMeans.assignAgentsFlag", true);
        this.clusterEntityIDsList = new ArrayList<>();
        this.centerIDs = new ArrayList<>();
        this.clusterEntitiesList = new HashMap<>();
        this.centerList = new ArrayList<>();
        this.entities = wi.getEntitiesOfType(
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT,
                StandardEntityURN.BUILDING,
                StandardEntityURN.REFUGE,
                StandardEntityURN.GAS_STATION,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE,
                StandardEntityURN.CIVILIAN,
                StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.POLICE_FORCE
        );
    }

    @Override
    public Clustering updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if(this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.centerList.clear();
        this.clusterEntitiesList.clear();
        return this;
    }

    @Override
    public Clustering precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if(this.getCountPrecompute() >= 2) {
            return this;
        }
        this.calcPathBased(this.repeatPrecompute);
        this.entities = null;
        // write
        precomputeData.setInteger(KEY_CLUSTER_SIZE, this.clusterSize);
        precomputeData.setEntityIDList(KEY_CLUSTER_CENTER, this.centerIDs);
        for(int i = 0; i < this.clusterSize; i++) {
            precomputeData.setEntityIDList(KEY_CLUSTER_ENTITY + i, this.clusterEntityIDsList.get(i));
        }
        precomputeData.setBoolean(KEY_ASSIGN_AGENT, this.assignAgentsFlag);
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if(this.getCountResume() >= 2) {
            return this;
        }
        this.entities = null;
        // read
        this.clusterSize = precomputeData.getInteger(KEY_CLUSTER_SIZE);
        this.centerIDs = new ArrayList<>(precomputeData.getEntityIDList(KEY_CLUSTER_CENTER));
        this.clusterEntityIDsList = new ArrayList<>(this.clusterSize);
        for(int i = 0; i < this.clusterSize; i++) {
            this.clusterEntityIDsList.add(i, precomputeData.getEntityIDList(KEY_CLUSTER_ENTITY + i));
        }
        this.assignAgentsFlag = precomputeData.getBoolean(KEY_ASSIGN_AGENT);
        return this;
    }

    @Override
    public Clustering preparate() {
        super.preparate();
        if(this.getCountPreparate() >= 2) {
            return this;
        }
        this.calcStandard(this.repeatPreparate);
        this.entities = null;
        return this;
    }

    @Override
    public int getClusterNumber() {
        //The number of clusters
        return this.clusterSize;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        return this.getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        for(int i = 0; i < this.clusterSize; i++) {
            if(this.clusterEntityIDsList.get(i).contains(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
    	List<StandardEntity> result = null;
    	if (index <= this.clusterEntitiesList.size() && index > 0){
    		result = this.clusterEntitiesList.get(index);
    	}
        if(result == null || result.isEmpty()) {
            List<EntityID> list = this.clusterEntityIDsList.get(index);
            result = new ArrayList<>(list.size());
            for(int i = 0; i < list.size(); i++) {
                result.add(i, this.worldInfo.getEntity(list.get(i)));
            }
            this.clusterEntitiesList.put(index, result);
        }
        return result;
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        return this.clusterEntityIDsList.get(index);
    }

    @Override
    public Clustering calc() {
        return this;
    }

    private void calcStandard(int repeat) {
        this.initShortestPath(this.worldInfo);

        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSize);
        this.clusterEntitiesList = new HashMap<>(this.clusterSize);

        //init list
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
            this.centerList.add(index, entityList.get(0));
        }
        System.out.println("[" + this.getClass().getSimpleName() + "] Cluster : " + this.clusterSize);
        //init center
        for (int index = 0; index < this.clusterSize; index++) {
        	
        	System.out.println (index);
             
            StandardEntity centerEntity;
            do {
            	int choice = getChoice(entityList);
            	centerEntity = entityList.get(choice);            	
            }while (this.centerList.contains(centerEntity));
            this.centerList.set(index, centerEntity);
            
            
        }
        //calc center
        for (int i = 0; i < repeat; i++) {
            this.clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSize; index++) {
                this.clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : entityList) {
                StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
                this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            }
            
           
            
            for (int index = 0; index < this.clusterSize; index++) {
                int sumX = 0, sumY = 0;
                
                Pair<Integer, Integer> oldCenter = this.worldInfo.getLocation(this.centerList.get(index).getID());
                
                for (StandardEntity entity : this.clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    Double priority = 0.0;
                    
                    switch(entity.getStandardURN()){
                    	case BUILDING: 			priority = LOW; break;
                    	case REFUGE: 			priority = MEDIUM_HIGH; break;
                    	case HYDRANT: 			priority = LOW; break;
                    	case GAS_STATION: 		priority = LOW; break;
                    	case ROAD: 				priority = MEDIUM_LOW; break;
                    	case CIVILIAN: 			priority = HIGH; break;
                    	case AMBULANCE_TEAM: 	priority = MEDIUM_HIGH; break;
                    	case FIRE_BRIGADE: 		priority = MEDIUM_HIGH; break;
                    	case POLICE_FORCE: 		priority = MEDIUM_HIGH; break;
                    	case AMBULANCE_CENTRE: 	priority = MEDIUM_LOW; break;
                    	case FIRE_STATION: 		priority = LOW; break;
                    	case POLICE_OFFICE: 	priority = LOW; break;

                    	default: priority = 0.0; break;
                    	
                    }
                    
                    Double valX = 0.0;
                    Double valY = 0.0;
                    
                    if(location.first() != oldCenter.first()){
                    	valX =  Math.min(location.first(),oldCenter.first()) + Math.abs(location.first() - oldCenter.first())*priority;
                    	if (valX < 0) valX=location.first()*1.0;
                    	
                    }else{ 
                    	valX = location.first()*1.0;
                    }
                    
                    if(location.second() != oldCenter.second()){
                    	valY =  Math.min(location.second(),oldCenter.second()) + Math.abs(location.second() - oldCenter.second())*priority;
                    	if (valY < 0) valY=location.second()*1.0;
                    	
                    }else{ 
                    	valY = location.second()*1.0;
                    }
                    
                    sumX += valX;
                    sumY += valY;
                }
                int centerX = sumX / this.clusterEntitiesList.get(index).size();
                int centerY = sumY / this.clusterEntitiesList.get(index).size();
                StandardEntity center = this.getNearEntityByLine(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY);
                if(center instanceof Area) {
                    this.centerList.set(index, center);
                }
                else if(center instanceof Human) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                }
                else if(center instanceof Blockade) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }

        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

        //set entity
        this.clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }

        //this.clusterEntitiesList.sort(comparing(List::size, reverseOrder()));

        if(this.assignAgentsFlag) {
            List<StandardEntity> ambulanceteamList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM));

            this.assignAgents(this.worldInfo, ambulanceteamList);
        }

        this.centerIDs = new ArrayList<>();
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0; index < this.clusterSize; index++) {
            List<StandardEntity> entities = this.clusterEntitiesList.get(index);
            List<EntityID> list = new ArrayList<>(entities.size());
            for(int i = 0; i < entities.size(); i++) {
                list.add(i, entities.get(i).getID());
            }
            this.clusterEntityIDsList.add(index, list);
        }
    }
    
    private int getChoice(List<StandardEntity> entityList) {
    	Random random = new Random();

		if(this.centerList.size() == 0){
			return Math.abs(random.nextInt()) % entityList.size();
		}
		
		List<Double> dists = new ArrayList<Double>();
		Double cumsum = 0.0;
		for(int i=0; i<entityList.size();i++){
			Double min_d = Double.MAX_VALUE;
			for(int j=0; j<centerList.size(); j++){
				Double cur_d = this.getDistance(this.worldInfo.getLocation(entityList.get(i).getID())  ,
												this.worldInfo.getLocation(entityList.get(j).getID())  ); 
				if (cur_d <= min_d){
					min_d = cur_d;
				}
			}
			dists.add(min_d);
			cumsum+=min_d;
		}
		
		List<Double> probs = new ArrayList<Double>();
		for(int i=0; i<dists.size();i++){
			probs.add( dists.get(i)/cumsum);
		}
		
		Double choice = random.nextDouble();
		int chosen = 0;
		for(int i=0; i<probs.size();i++){
			choice = choice-probs.get(i);
			if(choice <= 0){
				chosen = i;
				break;
			}
		}
		
		return chosen;
	}
    
    private double getDistance(Pair<Integer, Integer> from, Pair<Integer, Integer> to) {
        return getDistance(from.first(), from.second(), to.first(), to.second());
    }

    private void calcPathBased(int repeat) {
        this.initShortestPath(this.worldInfo);
        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSize);
        this.clusterEntitiesList = new HashMap<>(this.clusterSize);

        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
            this.centerList.add(index, entityList.get(0));
        }
        //init center
        for (int index = 0; index < this.clusterSize; index++) {
        	
        	System.out.println (index);
             
            StandardEntity centerEntity;
            do {
            	int choice = getChoice(entityList);
            	centerEntity = entityList.get(choice);            	
            }while (this.centerList.contains(centerEntity));
            this.centerList.set(index, centerEntity);
            
            
        }
        //calc center
        for (int i = 0; i < repeat; i++) {
            this.clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSize; index++) {
                this.clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : entityList) {
                StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
                this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            }
            
           
            
            for (int index = 0; index < this.clusterSize; index++) {
                int sumX = 0, sumY = 0;
                
                Pair<Integer, Integer> oldCenter = this.worldInfo.getLocation(this.centerList.get(index).getID());
                
                for (StandardEntity entity : this.clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    Double priority = 0.0;
                    
                    switch(entity.getStandardURN()){
                    	case BUILDING: 			priority = LOW; break;
                    	case REFUGE: 			priority = MEDIUM_HIGH; break;
                    	case HYDRANT: 			priority = LOW; break;
                    	case GAS_STATION: 		priority = LOW; break;
                    	case ROAD: 				priority = MEDIUM_LOW; break;
                    	case CIVILIAN: 			priority = HIGH; break;
                    	case AMBULANCE_TEAM: 	priority = MEDIUM_HIGH; break;
                    	case FIRE_BRIGADE: 		priority = MEDIUM_HIGH; break;
                    	case POLICE_FORCE: 		priority = MEDIUM_HIGH; break;
                    	case AMBULANCE_CENTRE: 	priority = MEDIUM_LOW; break;
                    	case FIRE_STATION: 		priority = LOW; break;
                    	case POLICE_OFFICE: 	priority = LOW; break;

                    	default: priority = 0.0; break;
                    	
                    }
                    
                    Double valX = 0.0;
                    Double valY = 0.0;
                    
                    if(location.first() != oldCenter.first()){
                    	valX =  Math.min(location.first(),oldCenter.first()) + Math.abs(location.first() - oldCenter.first())*priority;
                    	if (valX < 0) valX=location.first()*1.0;
                    	
                    }else{ 
                    	valX = location.first()*1.0;
                    }
                    
                    if(location.second() != oldCenter.second()){
                    	valY =  Math.min(location.second(),oldCenter.second()) + Math.abs(location.second() - oldCenter.second())*priority;
                    	if (valY < 0) valY=location.second()*1.0;
                    	
                    }else{ 
                    	valY = location.second()*1.0;
                    }
                    
                    sumX += valX;
                    sumY += valY;
                }
                int centerX = sumX / clusterEntitiesList.get(index).size();
                int centerY = sumY / clusterEntitiesList.get(index).size();

                //this.centerList.set(index, getNearEntity(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY));
                StandardEntity center = this.getNearEntity(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY);
                if (center instanceof Area) {
                    this.centerList.set(index, center);
                } else if (center instanceof Human) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                } else if (center instanceof Blockade) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }

        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

        this.clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntity(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }
        //this.clusterEntitiesList.sort(comparing(List::size, reverseOrder()));
        if (this.assignAgentsFlag) {
            List<StandardEntity> ambulanceTeamList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM));
            this.assignAgents(this.worldInfo, ambulanceTeamList);
        }

        this.centerIDs = new ArrayList<>();
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0; index < this.clusterSize; index++) {
            List<StandardEntity> entities = this.clusterEntitiesList.get(index);
            List<EntityID> list = new ArrayList<>(entities.size());
            for(int i = 0; i < entities.size(); i++) {
                list.add(i, entities.get(i).getID());
            }
            this.clusterEntityIDsList.add(index, list);
        }
    }

    private void assignAgents(WorldInfo world, List<StandardEntity> agentList) {
        int clusterIndex = 0;
        while (agentList.size() > 0) {
            StandardEntity center = this.centerList.get(clusterIndex);
            StandardEntity agent = this.getNearAgent(world, agentList, center);
            this.clusterEntitiesList.get(clusterIndex).add(agent);
            agentList.remove(agent);
            clusterIndex++;
            if (clusterIndex >= this.clusterSize) {
                clusterIndex = 0;
            }
        }
    }

    private StandardEntity getNearEntityByLine(WorldInfo world, List<StandardEntity> srcEntityList, StandardEntity targetEntity) {
        Pair<Integer, Integer> location = world.getLocation(targetEntity);
        return this.getNearEntityByLine(world, srcEntityList, location.first(), location.second());
    }

    private StandardEntity getNearEntityByLine(WorldInfo world, List<StandardEntity> srcEntityList, int targetX, int targetY) {
        StandardEntity result = null;
        for(StandardEntity entity : srcEntityList) {
            result = ((result != null) ? this.compareLineDistance(world, targetX, targetY, result, entity) : entity);
        }
        return result;
    }

    private StandardEntity getNearAgent(WorldInfo worldInfo, List<StandardEntity> srcAgentList, StandardEntity targetEntity) {
        StandardEntity result = null;
        for (StandardEntity agent : srcAgentList) {
            Human human = (Human)agent;
            if (result == null) {
                result = agent;
            }
            else {
                if (this.comparePathDistance(worldInfo, targetEntity, result, worldInfo.getPosition(human)).equals(worldInfo.getPosition(human))) {
                    result = agent;
                }
            }
        }
        return result;
    }

    private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, int targetX, int targetY) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.compareLineDistance(worldInfo, targetX, targetY, result, entity) : entity;
        }
        return result;
    }

    private Point2D getEdgePoint(Edge edge) {
        Point2D start = edge.getStart();
        Point2D end = edge.getEnd();
        return new Point2D(((start.getX() + end.getX()) / 2.0D), ((start.getY() + end.getY()) / 2.0D));
    }


    private double getDistance(double fromX, double fromY, double toX, double toY) {
        double dx = fromX - toX;
        double dy = fromY - toY;
        return Math.hypot(dx, dy);
    }

    private double getDistance(Pair<Integer, Integer> from, Point2D to) {
        return getDistance(from.first(), from.second(), to.getX(), to.getY());
    }

    private double getDistance(Pair<Integer, Integer> from, Edge to) {
        return getDistance(from, getEdgePoint(to));
    }

    private double getDistance(Point2D from, Point2D to) {
        return getDistance(from.getX(), from.getY(), to.getX(), to.getY());
    }

    private double getDistance(Edge from, Edge to) {
        return getDistance(getEdgePoint(from), getEdgePoint(to));
    }

    private StandardEntity compareLineDistance(WorldInfo worldInfo, int targetX, int targetY, StandardEntity first, StandardEntity second) {
        Pair<Integer, Integer> firstLocation = worldInfo.getLocation(first);
        Pair<Integer, Integer> secondLocation = worldInfo.getLocation(second);
        double firstDistance = getDistance(firstLocation.first(), firstLocation.second(), targetX, targetY);
        double secondDistance = getDistance(secondLocation.first(), secondLocation.second(), targetX, targetY);
        return (firstDistance < secondDistance ? first : second);
    }

    private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, StandardEntity targetEntity) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.comparePathDistance(worldInfo, targetEntity, result, entity) : entity;
        }
        return result;
    }

    private StandardEntity comparePathDistance(WorldInfo worldInfo, StandardEntity target, StandardEntity first, StandardEntity second) {
        double firstDistance = getPathDistance(worldInfo, shortestPath(target.getID(), first.getID()));
        double secondDistance = getPathDistance(worldInfo, shortestPath(target.getID(), second.getID()));
        return (firstDistance < secondDistance ? first : second);
    }

    private double getPathDistance(WorldInfo worldInfo, List<EntityID> path) {
        if (path == null) return Double.MAX_VALUE;
        if (path.size() <= 1) return 0.0D;

        double distance = 0.0D;
        int limit = path.size() - 1;

        Area area = (Area)worldInfo.getEntity(path.get(0));
        distance += getDistance(worldInfo.getLocation(area), area.getEdgeTo(path.get(1)));
        area = (Area)worldInfo.getEntity(path.get(limit));
        distance += getDistance(worldInfo.getLocation(area), area.getEdgeTo(path.get(limit - 1)));

        for(int i = 1; i < limit; i++) {
            area = (Area)worldInfo.getEntity(path.get(i));
            distance += getDistance(area.getEdgeTo(path.get(i - 1)), area.getEdgeTo(path.get(i + 1)));
        }
        return distance;
    }

    private void initShortestPath(WorldInfo worldInfo) {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        for (Entity next : worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.get(next.getID()).addAll(areaNeighbours);
            }
        }
        for (Map.Entry<EntityID, Set<EntityID>> graph : neighbours.entrySet()) {// fix graph
            for (EntityID entityID : graph.getValue()) {
                neighbours.get(entityID).add(graph.getKey());
            }
        }
        this.shortestPathGraph = neighbours;
    }

    private List<EntityID> shortestPath(EntityID start, EntityID... goals) {
        return shortestPath(start, Arrays.asList(goals));
    }

    private List<EntityID> shortestPath(EntityID start, Collection<EntityID> goals) {
        List<EntityID> open = new LinkedList<>();
        Map<EntityID, EntityID> ancestors = new HashMap<>();
        open.add(start);
        EntityID next;
        boolean found = false;
        ancestors.put(start, start);
        do {
            next = open.remove(0);
            if (isGoal(next, goals)) {
                found = true;
                break;
            }
            Collection<EntityID> neighbours = shortestPathGraph.get(next);
            if (neighbours.isEmpty()) continue;

            for (EntityID neighbour : neighbours) {
                if (isGoal(neighbour, goals)) {
                    ancestors.put(neighbour, next);
                    next = neighbour;
                    found = true;
                    break;
                }
                else if (!ancestors.containsKey(neighbour)) {
                    open.add(neighbour);
                    ancestors.put(neighbour, next);
                }
            }
        } while (!found && !open.isEmpty());
        if (!found) {
            // No path
            return null;
        }
        // Walk back from goal to start
        EntityID current = next;
        List<EntityID> path = new LinkedList<>();
        do {
            path.add(0, current);
            current = ancestors.get(current);
            if (current == null) throw new RuntimeException("Found a node with no ancestor! Something is broken.");
        } while (current != start);
        return path;
    }

    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }
}