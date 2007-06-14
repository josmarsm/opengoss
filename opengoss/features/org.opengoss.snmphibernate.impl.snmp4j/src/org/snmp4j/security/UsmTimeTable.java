/*_############################################################################
  _## 
  _##  SNMP4J - UsmTimeTable.java  
  _## 
  _##  Copyright 2003-2006  Frank Fock and Jochen Katz (SNMP4J.org)
  _##  
  _##  Licensed under the Apache License, Version 2.0 (the "License");
  _##  you may not use this file except in compliance with the License.
  _##  You may obtain a copy of the License at
  _##  
  _##      http://www.apache.org/licenses/LICENSE-2.0
  _##  
  _##  Unless required by applicable law or agreed to in writing, software
  _##  distributed under the License is distributed on an "AS IS" BASIS,
  _##  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  _##  See the License for the specific language governing permissions and
  _##  limitations under the License.
  _##  
  _##########################################################################*/





package org.snmp4j.security;

import java.io.*;
import java.util.*;

import org.snmp4j.log.*;
import org.snmp4j.mp.*;
import org.snmp4j.smi.*;

/**
 * The <code>UsmTimeTable</code> class is a singleton that stores USM user
 * information as part of the Local Configuration Datastore (LCD).
 *
 * @author Frank Fock
 * @version 1.2
 */
public class UsmTimeTable implements Serializable {

  private static final LogAdapter logger = LogFactory.getLogger(UsmTimeTable.class);

  private Hashtable table = new Hashtable(10);
  private long lastLocalTimeChange = System.currentTimeMillis();
  private UsmTimeEntry localTime;

  public UsmTimeTable(OctetString localEngineID, int engineBoots) {
    setLocalTime(new UsmTimeEntry(localEngineID, engineBoots, 0));
  }

  public void addEntry(final UsmTimeEntry entry) {
    table.put(entry.getEngineID(), entry);
  }

  public UsmTimeEntry getEntry(final OctetString engineID) {
    return (UsmTimeEntry) table.get(engineID);
  }

  public UsmTimeEntry getLocalTime() {
    UsmTimeEntry entry = new UsmTimeEntry(localTime.getEngineID(),
                                          localTime.getEngineBoots(),
                                          getEngineTime());
    entry.setTimeDiff(entry.getTimeDiff() * ( -1) + localTime.getTimeDiff());
    return entry;
  }

  private void setLocalTime(UsmTimeEntry localTime) {
    this.localTime = localTime;
    lastLocalTimeChange = System.currentTimeMillis();
  }

  /**
   * Sets the number of engine boots.
   * @param engineBoots
   *    the number of engine boots.
   * @since 1.2
   */
  public void setEngineBoots(int engineBoots) {
    this.localTime.setEngineBoots(engineBoots);
  }

  /**
   * Returns the number of seconds since the value of
   * the engineBoots object last changed. When incrementing this object's value
   * would cause it to exceed its maximum, engineBoots is incremented as if a
   * re-initialization had occurred, and this
   * object's value consequently reverts to zero.
   *
   * @return
   *    a positive integer value denoting the number of seconds since
   *    the engineBoots value has been changed.
   * @since 1.2
   */
  public int getEngineTime() {
    return (int)(((System.currentTimeMillis() - lastLocalTimeChange) / 1000) %
                 2147483648L);
  }

  /**
   * The number of times that the SNMP engine has (re-)initialized itself
   * since snmpEngineID was last configured.
   * @return
   *    the number of SNMP engine reboots.
   */
  public int getEngineBoots() {
    return localTime.getEngineBoots();
  }

  public synchronized UsmTimeEntry getTime(OctetString engineID) {
    if (localTime.getEngineID().equals(engineID)) {
      return getLocalTime();
    }
    UsmTimeEntry found = (UsmTimeEntry) table.get(engineID);
    if (found == null) {
      return null;
    }
    return new UsmTimeEntry(engineID, found.getEngineBoots(),
                            found.getTimeDiff() +
                            (int) (System.currentTimeMillis() / 1000));
  }

  /**
   * Removes the specified engine ID from the time cache.
   * @param engineID
   *    the engine ID of the remote SNMP engine to remove from this  time cache.
   */
  public void removeEntry(final OctetString engineID) {
    table.remove(engineID);
  }

  public synchronized int checkEngineID(OctetString engineID,
                                        boolean discoveryAllowed) {
    if (table.get(engineID) != null) {
      return SnmpConstants.SNMPv3_USM_OK;
    }
    else if (discoveryAllowed) {
      addEntry(new UsmTimeEntry(engineID, 0, 0));
      return SnmpConstants.SNMPv3_USM_OK;
    }
    return SnmpConstants.SNMPv3_USM_UNKNOWN_ENGINEID;
  }

  public synchronized int checkTime(final UsmTimeEntry entry) {
    int now = (int) (System.currentTimeMillis() / 1000);
    if (localTime.getEngineID().equals(entry.getEngineID())) {
      /* Entry found, we are authoritative */
      if ((localTime.getEngineBoots() == 2147483647) ||
          (localTime.getEngineBoots() != entry.getEngineBoots()) ||
          (Math.abs(now + localTime.getTimeDiff() - entry.getLatestReceivedTime())
           > 150)) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "CheckTime: received message outside time window (authorative):"+
              ((localTime.getEngineBoots() !=
                entry.getEngineBoots()) ? "engineBoots differ" :
               ""+(Math.abs(now + localTime.getTimeDiff() -
                            entry.getLatestReceivedTime()))+" > 150"));
        }
        return SnmpConstants.SNMPv3_USM_NOT_IN_TIME_WINDOW;
      }
      else {
        if (logger.isDebugEnabled()) {
          logger.debug("CheckTime: time ok (authorative)");
        }
        return SnmpConstants.SNMPv3_USM_OK;
      }
    }
    else {
      UsmTimeEntry time = (UsmTimeEntry) table.get(entry.getEngineID());
      if (time == null) {
        return SnmpConstants.SNMPv3_USM_UNKNOWN_ENGINEID;
      }
      if ((entry.getEngineBoots() < time.getEngineBoots()) ||
          ((entry.getEngineBoots() == time.getEngineBoots()) &&
           (time.getTimeDiff() + now >
            entry.getLatestReceivedTime() + 150)) ||
          (time.getEngineBoots() == 2147483647)) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "CheckTime: received message outside time window (non authorative)");
        }
        return SnmpConstants.SNMPv3_USM_NOT_IN_TIME_WINDOW;
      }
      else {
        if ((entry.getEngineBoots() > time.getEngineBoots()) ||
            ((entry.getEngineBoots() == time.getEngineBoots()) &&
             (entry.getLatestReceivedTime() > time.getLatestReceivedTime()))) {
          /* time ok, update values */
          time.setEngineBoots(entry.getEngineBoots());
          time.setLatestReceivedTime(entry.getLatestReceivedTime());
          time.setTimeDiff(entry.getLatestReceivedTime() - now);
        }
        if (logger.isDebugEnabled()) {
          logger.debug("CheckTime: time ok (non authorative)");
        }
        return SnmpConstants.SNMPv3_USM_OK;
      }
    }
  }
}
