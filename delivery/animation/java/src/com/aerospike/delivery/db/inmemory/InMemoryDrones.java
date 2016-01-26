package com.aerospike.delivery.db.inmemory;

import com.aerospike.delivery.Drone;
import com.aerospike.delivery.db.base.Drones;
import com.aerospike.delivery.util.OurExecutor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;


public class InMemoryDrones extends Drones {

  private final Map<Integer, Drone> contents;

  public InMemoryDrones() {
    contents = Collections.synchronizedMap(new HashMap<>());
  }

  public void add(Drone drone) {
    contents.put(drone.id, drone);
  }

  @Override
  public void clear() {
    super.clear();
    contents.clear();
  }

  @Override
  public int size(Drone.State state) {
    return 0;
  }

  public boolean put(Drone drone) {
    return true;
  }

  @Override
  public Drone getDroneWhereIdIs(int id) {
    return contents.get(id);
  }

  /**
   * This is simplistic for now.
   * It may have to function more like InMemoryJobs if we need to query
   * jobs in certain states.
   * @param drone
   * @param from
   * @param to
   * @return
   */
  @Override
  public boolean changeState(Drone drone, Drone.State from, Drone.State to) {
    if (to == Drone.State.Init) {
      drone.state = to;
      return true;
    }
    if (from == to) {
      System.out.printf("changeState from %s == to %s\n", from, to);
      return false;
    }
    if (drone.getState() != from) {
      return false;
    }
    drone.state = to;
    return true;
  }


  @Override
  public void foreachCached(Predicate<? super Drone> action) {
    Collection<Drone> values = contents.values();
    synchronized (contents) {
      for (Drone drone : values) {
        if (!action.test(drone))
          break;
      }
    }
  }


  @Override
  public BlockingQueue<Drone> makeQueueForRendering() {
    BlockingQueue<Drone> result = new LinkedBlockingQueue<>();
    OurExecutor.executor.execute(() -> {
      try {
        Collection<Drone> values = contents.values();
        synchronized (contents) {
          for (Drone drone : values) {
            result.add(drone.copy());
          }
        }
        result.add(Drone.NullDrone);
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    return result;
  }

}
