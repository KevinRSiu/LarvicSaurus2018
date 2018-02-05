package larvicsaurus.algorithm.clustering.hierarchical;

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

public class HKMeansPF extends StaticClustering {
    private static final String KEY_CLUSTER_SIZE = "sample.clustering.size";
    private static final String KEY_CLUSTER_CENTER = "sample.clustering.centers";
    private static final String KEY_CLUSTER_ENTITY = "sample.clustering.entities.";
    private static final String KEY_ASSIGN_AGENT = "sample.clustering.assign";

    private int repeatPrecompute;
    private int repeatPreparate;

    private Collection<StandardEntity> entities;
    private Collection<StandardEntity> top_entities;


    private List<StandardEntity> centerList;
    private List<EntityID> centerIDs;
    private Map<Integer, List<StandardEntity>> clusterEntitiesList;
    private List<List<EntityID>> clusterEntityIDsList;

    private int clusterSizeTop;
    private int clusterSizeBottom;

    private boolean assignAgentsFlag;

    private Map<EntityID, Set<EntityID>> shortestPathGraph;

    public HKMeansPF(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.repeatPrecompute = 5;// developData.getInteger("sample.module.SampleKMeans.repeatPrecompute", 5);
        this.repeatPreparate = developData.getInteger("sample.module.SampleKMeans.repeatPreparate", 30);
        //this.clusterSize = developData.getInteger("sample.module.SampleKMeans.clusterSize", 10);
        this.clusterSizeTop = wi.getEntitiesOfType(StandardEntityURN.REFUGE).size() <= 3 ? wi.getEntitiesOfType(StandardEntityURN.REFUGE).size() : (int) Math.ceil( wi.getEntitiesOfType(StandardEntityURN.REFUGE).size() / 3.0);
        this.clusterSizeBottom = 3;
        this.assignAgentsFlag = developData.getBoolean("sample.module.SampleKMeans.assignAgentsFlag", true);
        this.clusterEntityIDsList = new ArrayList<>();
        this.centerIDs = new ArrayList<>();
        this.clusterEntitiesList = new HashMap<>();
        this.centerList = new ArrayList<>();
        this.entities = wi.getEntitiesOfType(
                StandardEntityURN.REFUGE,
                StandardEntityURN.ROAD
        );
        this.top_entities = wi.getEntitiesOfType(
                StandardEntityURN.REFUGE
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
        precomputeData.setInteger(KEY_CLUSTER_SIZE, this.clusterSizeTop*this.clusterSizeBottom);
        precomputeData.setEntityIDList(KEY_CLUSTER_CENTER, this.centerIDs);
        for(int i = 0; i < this.clusterSizeTop * this.clusterSizeBottom; i++) {
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
        int clusterSize = precomputeData.getInteger(KEY_CLUSTER_SIZE);
        this.clusterSizeTop = clusterSize / this.clusterSizeBottom;
        this.centerIDs = new ArrayList<>(precomputeData.getEntityIDList(KEY_CLUSTER_CENTER));
        this.clusterEntityIDsList = new ArrayList<>(clusterSize);
        for(int i = 0; i < clusterSize; i++) {
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
        return this.clusterSizeTop * clusterSizeBottom;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        return this.getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        for(int i = 0; i < this.clusterSizeTop * clusterSizeBottom; i++) {
            if(this.clusterEntityIDsList.get(i).contains(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        List<StandardEntity> result = this.clusterEntitiesList.get(index);
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
        Random random = new Random();
        
        List<StandardEntity> top_entityList = new ArrayList<>(this.top_entities);
        List<StandardEntity> top_centerList = new ArrayList<>(this.clusterSizeTop);
        Map<Integer, List<StandardEntity>> top_clusterEntitiesList = new HashMap<>(this.clusterSizeTop);
        
        //init top list
        for (int index = 0; index < this.clusterSizeTop; index++){
        	top_clusterEntitiesList.put(index, new ArrayList<>());
        	top_centerList.add(index,null);
        }
        System.out.println("[" + this.getClass().getSimpleName() + "] Top Cluster : " + this.clusterSizeTop);
        
        //init top center 
        
        for (int index = 0; index < this.clusterSizeTop; index++) {
            StandardEntity centerEntity;
            do {
                centerEntity = top_entityList.get(Math.abs(random.nextInt()) % top_entityList.size());
            } while (top_centerList.contains(centerEntity));
            top_centerList.set(index, centerEntity);
        }
           
        
        //calc top center
        for (int i = 0; i < repeat; i++) {
        	top_clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSizeTop; index++) {
            	top_clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : top_entityList) {
                StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, top_centerList, entity);
                top_clusterEntitiesList.get(top_centerList.indexOf(tmp)).add(entity);
            }
            for (int index = 0; index < this.clusterSizeTop; index++) {
                int sumX = 0, sumY = 0;
                for (StandardEntity entity : top_clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    sumX += location.first();
                    sumY += location.second();
                }
                int centerX = sumX / top_clusterEntitiesList.get(index).size();
                int centerY = sumY / top_clusterEntitiesList.get(index).size();
                StandardEntity center = this.getNearEntityByLine(this.worldInfo, top_clusterEntitiesList.get(index), centerX, centerY);
                if(center instanceof Area) {
                    top_centerList.set(index, center);
                }
                else if(center instanceof Human) {
                    top_centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                }
                else if(center instanceof Blockade) {
                    top_centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }
        
        //assign top clusters
        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSizeTop * this.clusterSizeBottom);
        this.clusterEntitiesList = new HashMap<>(this.clusterSizeTop * this.clusterSizeBottom);
        
        top_clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSizeTop; index++) {
        	top_clusterEntitiesList.put(index, new ArrayList<>());
        }
        
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, top_centerList, entity);
            top_clusterEntitiesList.get(top_centerList.indexOf(tmp)).add(entity);
        }
        
        /*
        for(int index=0; index<this.clusterSizeTop; index++){
        	
            List<StandardEntity> cur_entityList = new ArrayList<>(top_clusterEntitiesList.get(index));
            System.out.println(index + " : " + cur_entityList);
  
        }*/
        
        
        for(int index=0; index<this.clusterSizeTop; index++){
        	
            List<StandardEntity> cur_entityList = new ArrayList<>(top_clusterEntitiesList.get(index));
            
            //init bottom list
            for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            	this.clusterEntitiesList.put(index*this.clusterSizeBottom+jndex, new ArrayList<>());
            	this.centerList.add(index*this.clusterSizeBottom+jndex, null);
            }
            System.out.println("[" + this.getClass().getSimpleName() + "] Cluster Bottom: " + this.clusterSizeBottom);
            // init bottom center
            for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            	StandardEntity centerEntity;
            	do {
            		centerEntity = cur_entityList.get(Math.abs(random.nextInt()) % cur_entityList.size());
            	}while (this.centerList.contains(centerEntity));
                this.centerList.set(index*this.clusterSizeBottom+jndex, centerEntity);

            }
            
            System.out.println(this.centerList);

            
            //calc bottom center
            
            for (int i=0; i<repeat; i++){
            	
            	for(int jndex=0;jndex < this.clusterSizeBottom; jndex++){
            		this.clusterEntitiesList.get(index*this.clusterSizeBottom+jndex).clear();
            	}
            	
            	for(StandardEntity entity: cur_entityList){
            		StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList.subList(index*this.clusterSizeBottom, index*this.clusterSizeBottom+clusterSizeBottom), entity);
            		this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            	}
            	
            	
            	for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            		int sumX = 0, sumY = 0;
            		//System.out.println(this.clusterEntitiesList.get(index*this.clusterSizeTop + jndex));
            		for(StandardEntity entity : this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex)){
            			Pair<Integer,Integer> location = this.worldInfo.getLocation(entity);
            			sumX += location.first();
            			sumY += location.second();
            		}
            		int centerX = sumX / this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex).size();
            		int centerY = sumY / this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex).size();
            		
            		StandardEntity center = this.getNearEntityByLine(this.worldInfo, this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex), centerX, centerY);
            		if(center instanceof Area) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, center);
                    }
                    else if(center instanceof Human) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, this.worldInfo.getEntity(((Human) center).getPosition()));
                    }
                    else if(center instanceof Blockade) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                    }
            	}
            	
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
            
        	
        }
        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

         //set entity
        this.clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSizeTop*this.clusterSizeBottom; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }

        //this.clusterEntitiesList.sort(comparing(List::size, reverseOrder()));

        if(this.assignAgentsFlag) {
            List<StandardEntity> policeForceList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
            this.assignAgents(this.worldInfo, policeForceList);
        }

        this.centerIDs = new ArrayList<>();
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0; index < this.clusterSizeTop * this.clusterSizeBottom; index++) {
            List<StandardEntity> entities = this.clusterEntitiesList.get(index);
            List<EntityID> list = new ArrayList<>(entities.size());
            for(int i = 0; i < entities.size(); i++) {
                list.add(i, entities.get(i).getID());
            }
            this.clusterEntityIDsList.add(index, list);
        }

    }

    private void calcPathBased(int repeat) {
        this.initShortestPath(this.worldInfo);
        Random random = new Random();
        
        List<StandardEntity> top_entityList = new ArrayList<>(this.top_entities);
        List<StandardEntity> top_centerList = new ArrayList<>(this.clusterSizeTop);
        Map<Integer, List<StandardEntity>> top_clusterEntitiesList = new HashMap<>(this.clusterSizeTop);
        
        //init top list
        for (int index = 0; index < this.clusterSizeTop; index++){
        	top_clusterEntitiesList.put(index, new ArrayList<>());
        	top_centerList.add(index,null);
        }
        System.out.println("[" + this.getClass().getSimpleName() + "] Top Cluster : " + this.clusterSizeTop);
        
        //init top center 
        
        for (int index = 0; index < this.clusterSizeTop; index++) {
            StandardEntity centerEntity;
            do {
                centerEntity = top_entityList.get(Math.abs(random.nextInt()) % top_entityList.size());
            } while (top_centerList.contains(centerEntity));
            top_centerList.set(index, centerEntity);
        }
           
        
        //calc top center
        for (int i = 0; i < repeat; i++) {
        	top_clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSizeTop; index++) {
            	top_clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : top_entityList) {
                StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, top_centerList, entity);
                top_clusterEntitiesList.get(top_centerList.indexOf(tmp)).add(entity);
            }
            for (int index = 0; index < this.clusterSizeTop; index++) {
                int sumX = 0, sumY = 0;
                for (StandardEntity entity : top_clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    sumX += location.first();
                    sumY += location.second();
                }
                int centerX = sumX / top_clusterEntitiesList.get(index).size();
                int centerY = sumY / top_clusterEntitiesList.get(index).size();
                StandardEntity center = this.getNearEntityByLine(this.worldInfo, top_clusterEntitiesList.get(index), centerX, centerY);
                if(center instanceof Area) {
                    top_centerList.set(index, center);
                }
                else if(center instanceof Human) {
                    top_centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                }
                else if(center instanceof Blockade) {
                    top_centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }
        
        //assign top clusters
        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSizeTop * this.clusterSizeBottom);
        this.clusterEntitiesList = new HashMap<>(this.clusterSizeTop * this.clusterSizeBottom);
        
        top_clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSizeTop; index++) {
        	top_clusterEntitiesList.put(index, new ArrayList<>());
        }
        
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, top_centerList, entity);
            top_clusterEntitiesList.get(top_centerList.indexOf(tmp)).add(entity);
        }
        
        /*
        for(int index=0; index<this.clusterSizeTop; index++){
        	
            List<StandardEntity> cur_entityList = new ArrayList<>(top_clusterEntitiesList.get(index));
            System.out.println(index + " : " + cur_entityList);
  
        }*/
        
        
        for(int index=0; index<this.clusterSizeTop; index++){
        	
            List<StandardEntity> cur_entityList = new ArrayList<>(top_clusterEntitiesList.get(index));
            
            //init bottom list
            for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            	this.clusterEntitiesList.put(index*this.clusterSizeBottom+jndex, new ArrayList<>());
            	this.centerList.add(index*this.clusterSizeBottom+jndex, null);
            }
            System.out.println("[" + this.getClass().getSimpleName() + "] Cluster Bottom: " + this.clusterSizeBottom);
            // init bottom center
            for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            	StandardEntity centerEntity;
            	do {
            		centerEntity = cur_entityList.get(Math.abs(random.nextInt()) % cur_entityList.size());
            	}while (this.centerList.contains(centerEntity));
                this.centerList.set(index*this.clusterSizeBottom+jndex, centerEntity);

            }
            
            System.out.println(this.centerList);

            
            //calc bottom center
            
            for (int i=0; i<repeat; i++){
            	
            	for(int jndex=0;jndex < this.clusterSizeBottom; jndex++){
            		this.clusterEntitiesList.get(index*this.clusterSizeBottom+jndex).clear();
            	}
            	
            	for(StandardEntity entity: cur_entityList){
            		StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList.subList(index*this.clusterSizeBottom, index*this.clusterSizeBottom+clusterSizeBottom), entity);
            		this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            	}
            	
            	
            	for(int jndex = 0; jndex < this.clusterSizeBottom; jndex++){
            		int sumX = 0, sumY = 0;
            		//System.out.println(this.clusterEntitiesList.get(index*this.clusterSizeTop + jndex));
            		for(StandardEntity entity : this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex)){
            			Pair<Integer,Integer> location = this.worldInfo.getLocation(entity);
            			sumX += location.first();
            			sumY += location.second();
            		}
            		int centerX = sumX / this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex).size();
            		int centerY = sumY / this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex).size();
            		
            		StandardEntity center = this.getNearEntityByLine(this.worldInfo, this.clusterEntitiesList.get(index*this.clusterSizeBottom + jndex), centerX, centerY);
            		if(center instanceof Area) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, center);
                    }
                    else if(center instanceof Human) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, this.worldInfo.getEntity(((Human) center).getPosition()));
                    }
                    else if(center instanceof Blockade) {
                        this.centerList.set(index*this.clusterSizeBottom + jndex, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                    }
            	}
            	
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
            
        	
        }
        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

         //set entity
        this.clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSizeTop*this.clusterSizeBottom; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }

        //this.clusterEntitiesList.sort(comparing(List::size, reverseOrder()));

        if(this.assignAgentsFlag) {
        	List<StandardEntity> policeForceList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
            this.assignAgents(this.worldInfo, policeForceList);
        }

        this.centerIDs = new ArrayList<>();
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0; index < this.clusterSizeTop * this.clusterSizeBottom; index++) {
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
            if (clusterIndex >= this.clusterSizeTop*clusterSizeBottom) {
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

    /*
      private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, int targetX, int targetY) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.compareLineDistance(worldInfo, targetX, targetY, result, entity) : entity;
        }
        return result;
    }
	*/
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

    /* private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, StandardEntity targetEntity) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.comparePathDistance(worldInfo, targetEntity, result, entity) : entity;
        }
        return result;
    } */

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
