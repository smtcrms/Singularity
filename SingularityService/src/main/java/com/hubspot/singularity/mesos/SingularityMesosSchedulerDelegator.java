package com.hubspot.singularity.mesos;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

public class SingularityMesosSchedulerDelegator implements Scheduler {

  private final static Logger LOG = LoggerFactory.getLogger(SingularityMesosScheduler.class);

  private SchedulerDriver driver;
  private final SingularityMesosScheduler scheduler;
  private final SingularityStartup startup;

  private final Lock startupLock;
  private final Lock lock;

  private enum SchedulerState {
    STARTUP, RUNNING;
  }

  private volatile SchedulerState state;
  private final List<Protos.TaskStatus> queuedUpdates;

  @Inject
  public SingularityMesosSchedulerDelegator(SingularityMesosScheduler scheduler, SingularityStartup startup) {
    this.scheduler = scheduler;
    this.startup = startup;

    this.queuedUpdates = Lists.newArrayList();

    this.lock = new ReentrantLock();
    this.startupLock = new ReentrantLock();

    this.state = SchedulerState.STARTUP;
  }

  // TODO should the lock wait on a timeout and then notify that it's taking a while?

  private void lock() {
    lock.lock();
  }

  private void release() {
    lock.unlock();
  }

  private void flushLogs() {
    LOG.info("Attempting to flush logs and wait for 5 seconds...");
    
    ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
    // Check for logback implementation of slf4j
    if (loggerFactory instanceof LoggerContext) {
      LoggerContext context = (LoggerContext) loggerFactory;
      context.stop();
    }

    try {
      Thread.sleep(5000);
    } catch (Exception e) {

    }
  }

  private void exit() {
    if (driver != null) {
      driver.abort();
    }

    System.exit(1);
  }

  private void handleUncaughtSchedulerException(Throwable t) {
    LOG.error("Scheduler threw an uncaught exception - exiting", t);

    abort();
  }

  private void abort() {
    flushLogs();

    exit();
  }

  private void startup(SchedulerDriver driver, MasterInfo masterInfo) {
    if (state != SchedulerState.STARTUP) {
      LOG.info("Asked to startup - but have already startedup - aborting");
      abort();
    }

    this.driver = driver;

    startup.startup(masterInfo);

    startupLock.lock(); // ensure we aren't adding queued updates. calls to status updates are now blocked.

    try {
      state = SchedulerState.RUNNING; // calls to resource offers will now block, since we are already scheduler locked.

      for (Protos.TaskStatus status : queuedUpdates) {
        scheduler.statusUpdate(driver, status);
      }
      
    } finally {
      startupLock.unlock();
    }
  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
    lock();

    try {
      startup(driver, masterInfo);

      scheduler.registered(driver, frameworkId, masterInfo);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    lock();

    try {
      startup(driver, masterInfo);

      scheduler.reregistered(driver, masterInfo);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    if (state == SchedulerState.STARTUP) {
      LOG.info(String.format("Scheduler is in startup mode, declining %s offer(s)", offers.size()));

      for (Protos.Offer offer : offers) {
        driver.declineOffer(offer.getId());
      }

      return;
    }

    lock();

    try {
      scheduler.resourceOffers(driver, offers);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    lock();

    try {
      scheduler.offerRescinded(driver, offerId);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    startupLock.lock();

    try {
      if (state == SchedulerState.STARTUP) {
        LOG.info(String.format("Scheduler is starting up, queueing an update %s - %s queued updates so far", status, queuedUpdates.size()));

        queuedUpdates.add(status);

        return;
      }
    } finally {
      startupLock.unlock();
    }

    lock();

    try {
      scheduler.statusUpdate(driver, status);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId, SlaveID slaveId, byte[] data) {
    lock();

    try {
      scheduler.frameworkMessage(driver, executorId, slaveId, data);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    lock();

    try {
      scheduler.disconnected(driver);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    lock();

    try {
      scheduler.slaveLost(driver, slaveId);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorId, SlaveID slaveId, int status) {
    lock();

    try {
      scheduler.executorLost(driver, executorId, slaveId, status);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    lock();

    try {
      scheduler.error(driver, message);
    } catch (Throwable t) {
      handleUncaughtSchedulerException(t);
    } finally {
      release();
    }
  }

}
