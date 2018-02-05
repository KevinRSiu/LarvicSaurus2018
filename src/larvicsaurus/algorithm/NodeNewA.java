package larvicsaurus.algorithm;


import java.util.Collection;

import adf.agent.info.WorldInfo;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class NodeNewA{
	EntityID id;
	EntityID parent;
	double cost;
	double heuristic;
	double alpha;
	double beta;
	double gamma;
	boolean obstruccion;
	boolean incendios;
	boolean civiles;
	boolean refuge;
	double policias;
	double bomberos;
	double ambulancias;
	double pesos_ambulancia;
	double pesos_bomberos;
	double pesos_policias;
	double pesos;
	double pesos_obstruccion;
	double pesos_incendios;
	double pesos_victimas;
	
	public NodeNewA(NodeNewA from, EntityID id, WorldInfo wi, Collection<EntityID> targets){
		this.id = id;
		this.alpha = 0.9; //Ambulancia
		this.beta = 0.7;  //Bomberos
		this.gamma = 0.5; //Policias
		this.ambulancias = 0.5;
		this.bomberos = 0.3;
		this.policias = 0.2;
		StandardEntity entity = wi.getEntity(id);
		this.obstruccion = entity.getStandardURN().equals(StandardEntityURN.BLOCKADE);
		this.refuge =  entity.getStandardURN().equals(StandardEntityURN.REFUGE);
		this.incendios = entity instanceof Building && ((Building)entity).isOnFire();
		Human h = (Human) entity; 
		this.civiles = h.isHPDefined() && h.isBuriednessDefined() && h.getHP() > 0 && h.getBuriedness() > 0;
		
		if(from == null){
			this.cost = 0;
		}else{
			if(this.obstruccion){
				this.pesos_obstruccion = 0.8;
				this.pesos_incendios = 0.1;
				this.pesos_victimas = 0.1;
			}else if(this.civiles){
				this.pesos_obstruccion = 0.1;
				this.pesos_incendios = 0.1;
				this.pesos_victimas = 0.8;
			}else if(this.incendios){
				this.pesos_obstruccion = 0.1;
				this.pesos_incendios = 0.8;
				this.pesos_victimas = 0.1;
			}else if(this.refuge){
				this.pesos_obstruccion = 0.5;
				this.pesos_incendios = 0.5;
				this.pesos_victimas = 0.5;
			}else{
				this.pesos_obstruccion = 0.1;
				this.pesos_incendios = 0.1;
				this.pesos_victimas = 0.1;
			}
				
			this.parent = from.getID();
			this.pesos_ambulancia = this.alpha * this.ambulancias * this.pesos_victimas;
			this.pesos_bomberos = this.beta * this.bomberos * this.pesos_incendios;
			this.pesos_policias = this.gamma * this.policias * this.pesos_obstruccion;
			this.pesos = this.pesos_ambulancia + this.pesos_bomberos + this.pesos_policias;
			this.cost = from.getCost() + wi.getDistance(from.getID(), id) + pesos;
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
