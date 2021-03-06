/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import java.util
import scala.collection.JavaConversions._
import scala.util.parsing.json.JSONObject
import scala.collection
import org.apache.mesos.Protos.{Resource, Offer}
import java.util.{Date, UUID}
import ly.stealth.mesos.kafka.Broker.Failover
import Util.{Wildcard, Period, Str}

class Broker(_id: String = "0") {
  var id: String = _id
  @volatile var active: Boolean = false

  var host: String = null
  var cpus: Double = 0.5
  var mem: Long = 128
  var heap: Long = 128

  var attributes: String = null
  var options: String = null

  var failover: Failover = new Failover()

  def attributeMap: util.Map[String, String] = Util.parseMap(attributes, ";", ":")

  def optionMap(overrides: util.Map[String, String] = null): util.Map[String, String] = {
    val result = Util.parseMap(options, ";", "=")

    for ((k, v) <- result) {
      var nv = v
      nv = nv.replace("$id", id)
      if (host != null) nv = nv.replace("$host", host)

      result.put(k, nv)
    }

    if (overrides != null) result.putAll(overrides)

    if (!result.containsKey("log.dirs"))
      result.put("log.dirs", "kafka-logs")

    result
  }

  @volatile var task: Broker.Task = null

  def matches(offer: Offer): Boolean = {
    if (host != null && !new Wildcard(host).matches(offer.getHostname)) return false

    // check resources
    val offerResources = new util.HashMap[String, Resource]()
    for (resource <- offer.getResourcesList) offerResources.put(resource.getName, resource)

    val cpusResource = offerResources.get("cpus")
    if (cpusResource == null || cpusResource.getScalar.getValue < cpus) return false

    val memResource = offerResources.get("mem")
    if (memResource == null || memResource.getScalar.getValue < mem) return false

    // check attributes
    val offerAttributes = new util.HashMap[String, String]()
    for (attribute <- offer.getAttributesList)
      if (attribute.hasText) offerAttributes.put(attribute.getName, attribute.getText.getValue)

    for ((name, value) <- attributeMap) {
      if (!offerAttributes.containsKey(name)) return false
      if (!new Wildcard(value).matches(offerAttributes.get(name))) return false
    }

    true
  }
  
  def shouldStart(offer: Offer, now: Date = new Date()): Boolean = {
    active && task == null && matches(offer) && !failover.isWaitingDelay(now) 
  }

  def shouldStop: Boolean = !active

  def state(now: Date = new Date()): String = {
    if (active) {
      if (task != null && task.running) return "running"

      if (failover.isWaitingDelay(now)) {
        var s = "failed " + failover.failures
        if (failover.maxTries != null) s += "/" + failover.maxTries
        s += " " + Str.dateTime(failover.failureTime)
        s += ", next start " + Str.dateTime(failover.delayExpires)
        return s
      }

      if (failover.failures > 0) {
        var s = "starting " + (failover.failures + 1)
        if (failover.maxTries != null) s += "/" + failover.maxTries
        s += ", failed " + Str.dateTime(failover.failureTime)
        return s
      }

      return "starting"
    }

    if (task != null) return "stopping"
    "stopped"
  }

  def waitFor(running: Boolean, timeout: Period): Boolean = {
    def matches: Boolean = if (running) task != null && task.running else task == null

    var t = timeout.ms
    while (t > 0 && !matches) {
      val delay = Math.min(100, t)
      Thread.sleep(delay)
      t -= delay
    }

    matches
  }

  def copy(): Broker = {
    val broker: Broker = new Broker()
    broker.id = id
    broker.active = active

    broker.host = host
    broker.cpus = cpus
    broker.mem = mem
    broker.heap = heap

    broker.attributes = attributes
    broker.options = options

    broker.failover = failover.copy
    if (task != null) broker.task = task.copy

    broker
  }

  def fromJson(node: Map[String, Object]): Unit = {
    id = node("id").asInstanceOf[String]
    active = node("active").asInstanceOf[Boolean]

    if (node.contains("host")) host = node("host").asInstanceOf[String]
    cpus = node("cpus").asInstanceOf[Number].doubleValue()
    mem = node("mem").asInstanceOf[Number].longValue()
    heap = node("heap").asInstanceOf[Number].longValue()

    if (node.contains("attributes")) attributes = node("attributes").asInstanceOf[String]
    if (node.contains("options")) options = node("options").asInstanceOf[String]

    failover.fromJson(node("failover").asInstanceOf[Map[String, Object]])

    if (node.contains("task")) {
      task = new Broker.Task()
      task.fromJson(node("task").asInstanceOf[Map[String, Object]])
    }
  }

  def toJson: JSONObject = {
    val obj = new collection.mutable.LinkedHashMap[String, Any]()
    obj("id") = id
    obj("active") = active

    if (host != null) obj("host") = host
    obj("cpus") = cpus
    obj("mem") = mem
    obj("heap") = heap

    if (attributes != null) obj("attributes") = attributes
    if (options != null) obj("options") = options

    obj("failover") = failover.toJson
    if (task != null) obj("task") = task.toJson

    new JSONObject(obj.toMap)
  }
}

object Broker {
  def nextTaskId(broker: Broker): String = "broker-" + broker.id + "-" + UUID.randomUUID()
  def nextExecutorId(broker: Broker): String = "broker-" + broker.id + "-" + UUID.randomUUID()
  
  def idFromTaskId(taskId: String): String = {
    val parts: Array[String] = taskId.split("-")
    if (parts.length < 2) throw new IllegalArgumentException(taskId)
    parts(1)
  }

  class Failover(_delay: Period = new Period("10s"), _maxDelay: Period = new Period("60s")) {
    var delay: Period = _delay
    var maxDelay: Period = _maxDelay
    var maxTries: Integer = null

    @volatile var failures: Int = 0
    @volatile var failureTime: Date = null

    def currentDelay: Period = {
      if (failures == 0) return new Period("0")

      val multiplier = 1 << (failures - 1)
      val d = delay.ms * multiplier

      if (d > maxDelay.ms) maxDelay else new Period(delay.value * multiplier + delay.unit)
    }

    def delayExpires: Date = {
      if (failures == 0) return new Date(0)
      new Date(failureTime.getTime + currentDelay.ms)
    }

    def isWaitingDelay(now: Date = new Date()): Boolean = delayExpires.getTime > now.getTime

    def isMaxTriesExceeded: Boolean = {
      if (maxTries == null) return false
      failures >= maxTries
    }

    def registerFailure(now: Date = new Date()): Unit = {
      failures += 1
      failureTime = now
    }

    def resetFailures(): Unit = {
      failures = 0
      failureTime = null
    }
    
    def copy: Failover = {
      val failover = new Failover()

      failover.delay = delay
      failover.maxDelay = maxDelay
      failover.maxTries = maxTries

      failover.failures = failures
      failover.failureTime = failureTime
      failover
    }

    def fromJson(node: Map[String, Object]): Unit = {
      delay = new Period(node("delay").asInstanceOf[String])
      maxDelay = new Period(node("maxDelay").asInstanceOf[String])
      if (node.contains("maxTries")) maxTries = node("maxTries").asInstanceOf[Number].intValue()
      
      if (node.contains("failures")) failures = node("failures").asInstanceOf[Number].intValue()
      if (node.contains("failureTime")) failureTime = new Date(node("failureTime").asInstanceOf[Number].longValue())
    }

    def toJson: JSONObject = {
      val obj = new collection.mutable.LinkedHashMap[String, Any]()

      obj("delay") = "" + delay
      obj("maxDelay") = "" + maxDelay
      if (maxTries != null) obj("maxTries") = maxTries

      if (failures != 0) obj("failures") = failures
      if (failureTime != null) obj("failureTime") = failureTime.getTime
      
      new JSONObject(obj.toMap)
    }
  }

  class Task(_id: String = null, _host: String = null, _port: Int = -1, _running: Boolean = false) {
    var id: String = _id
    @volatile var running: Boolean = _running
    var host: String = _host
    var port: Int = _port

    def endpoint: String = host + ":" + port

    def copy: Task = {
      val task = new Task()

      task.id = id
      task.running = running
      task.host = host
      task.port = port

      task
    }

    def fromJson(node: Map[String, Object]): Unit = {
      id = node("id").asInstanceOf[String]
      running = node("running").asInstanceOf[Boolean]
      host = node("host").asInstanceOf[String]
      port = node("port").asInstanceOf[Number].intValue()
    }

    def toJson: JSONObject = {
      val obj = new collection.mutable.LinkedHashMap[String, Any]()

      obj("id") = id
      obj("running") = running
      obj("host") = host
      obj("port") = port

      new JSONObject(obj.toMap)
    }
  }
}