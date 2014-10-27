package name.dmitrym.ags.server

import org.jivesoftware.smack.{ConnectionConfiguration, ConnectionListener, PacketListener, XMPPConnection}
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.filter.PacketTypeFilter
import org.jivesoftware.smack.packet.{DefaultPacketExtension,Message,Packet,PacketExtension}
import org.jivesoftware.smack.provider.{PacketExtensionProvider, ProviderManager}
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.util.StringUtils

import org.json.simple.JSONValue
import org.xmlpull.v1.XmlPullParser

import java.util.UUID
import javax.net.ssl.SSLSocketFactory

import scala.collection.mutable.HashMap

object SmackCcsClient2 extends App {
  val GCM_SERVER = "gcm.googleapis.com"
  val GCM_PORT = 5235

  val GCM_ELEMENT_NAME = "gcm"
  val GCM_NAMESPACE = "google:mobile:data"

  val senderId = 825512148130L // your GCM sender id
  val password = "AIzaSyDOXjc13utAaMMvz42YUsBgAnJ0sDqULOc"

  var connectionDraining = false

  class GcmPacketExtension(json: String) extends DefaultPacketExtension(GCM_ELEMENT_NAME, GCM_NAMESPACE) {
    def getJson():String = json
    def toPacket():Packet = {
      val message = new Message()
      message.addExtension(this)
      message
    }
    override def toXML():String = {
      s"""<$GCM_ELEMENT_NAME xmlns=\"$GCM_NAMESPACE\">${StringUtils.escapeForXML(json)}</$GCM_ELEMENT_NAME>"""
    }
  }

  ProviderManager.addExtensionProvider(GCM_ELEMENT_NAME, GCM_NAMESPACE,
    new PacketExtensionProvider() {
      def parseExtension(parser: XmlPullParser):PacketExtension = new GcmPacketExtension(parser.nextText)
    })

  def nextMessageId():String = "m-" + UUID.randomUUID().toString()

  def createJsonMessage(to:String, messageId:String, payload:Map[String,String], collapseKey:String, timeToLive:Option[Long], delayWhileIdle:Boolean):String = {
      val message = HashMap[String, Object]()
      message.put("to", to)
      if (collapseKey != null) {
          message.put("collapse_key", collapseKey)
      }
      if (!timeToLive.isEmpty) {
          message.put("time_to_live", timeToLive.toString)
      }
      if (delayWhileIdle) {
          message.put("delay_while_idle", true.toString)
      }
      message.put("message_id", messageId)
      message.put("data", payload)
      JSONValue.toJSONString(message)
  }

  def handleUpstreamMessage(jsonObject:Map[String, Object]) {
      // PackageName of the application that sent this message.
      val category = jsonObject.get("category").asInstanceOf[String]
      val from = jsonObject.get("from").asInstanceOf[String]
      val payload = jsonObject.get("data").asInstanceOf[Map[String,String]] + ("ECHO" -> ("Application: " + category))

      // Send an ECHO response back
      val echo = createJsonMessage(from, nextMessageId(), payload, "echo:CollapseKey", None, false)

      sendDownstreamMessage(echo)
  }

  def sendDownstreamMessage(jsonRequest: String):Boolean = {
      if (!connectionDraining) {
          send(jsonRequest)
          true
      } else false
  }

  def send(jsonRequest: String) {
      val request = new GcmPacketExtension(jsonRequest).toPacket()
      connection.sendPacket(request)
  }

  def connect(senderId:Long, apiKey:String):XMPPTCPConnection = {
      val config = new ConnectionConfiguration(GCM_SERVER, GCM_PORT)
      config.setSecurityMode(SecurityMode.enabled)
      config.setReconnectionAllowed(true)
      config.setRosterLoadedAtLogin(false)
      config.setSendPresence(false)
      config.setSocketFactory(SSLSocketFactory.getDefault())

      val connection = new XMPPTCPConnection(config)
      connection.addConnectionListener(new ConnectionListener(){
        override def connected(xmppConnection: XMPPConnection) {
            println("Connected.")
        }

        override def authenticated(xmppConnection: XMPPConnection) {
            println("Authenticated.")
        }

        override def reconnectionSuccessful() {
            println("Reconnecting..")
        }

        override def reconnectionFailed(e: Exception) {
            println(s"Reconnection failed.. $e")
        }

        override def reconnectingIn(seconds: Int) {
            println(s"Reconnecting in $seconds secs")
        }

        override def connectionClosedOnError(e: Exception) {
            println(s"Connection closed on error. $e")
        }

        override def connectionClosed() {
            println("Connection closed.")
        }
      })

      // Handle incoming packets
      connection.addPacketListener(new PacketListener() {
          override def processPacket(packet: Packet) {
              val incomingMessage = packet.asInstanceOf[Message]
              val gcmPacket = incomingMessage.getExtension(GCM_NAMESPACE).asInstanceOf[GcmPacketExtension]
              val json = gcmPacket.getJson()
              try {
                  val jsonObject = JSONValue.parseWithException(json).asInstanceOf[Map[String,Object]]

                  // present for "ack"/"nack", null otherwise
                  val messageType = jsonObject.get("message_type")

                  if (messageType == null) {
                      // Normal upstream data message
                      handleUpstreamMessage(jsonObject)

                      // Send ACK to CCS
                      val messageId = jsonObject.get("message_id").asInstanceOf[String]
                      val from = jsonObject.get("from").asInstanceOf[String]
                      val ack = createJsonAck(from, messageId).asInstanceOf[String]
                      send(ack)
                  } else if ("ack".equals(messageType.toString())) {
                      // Process Ack
                      handleAckReceipt(jsonObject);
                  } else if ("nack".equals(messageType.toString())) {
                      // Process Nack
                      handleNackReceipt(jsonObject);
                  } else if ("control".equals(messageType.toString())) {
                      // Process control message
                      handleControlMessage(jsonObject);
                  }
              } catch {
                case _:Throwable => //Ignore
              }
          }
      }, new PacketTypeFilter(classOf[Message]))

      println("Packet listener added")
      
      connection.connect()
      println("After connect")

      connection.login(senderId + "@gcm.googleapis.com", apiKey)

      println("Logged in")
      connection
  }

  def createJsonAck(to:String, messageId:String):String = {
      val message = HashMap[String, Object]()
      message.put("message_type", "ack")
      message.put("to", to)
      message.put("message_id", messageId)
      JSONValue.toJSONString(message)
  }

  def handleAckReceipt(jsonObject: Map[String, Object]) {
      val messageId = jsonObject.get("message_id").asInstanceOf[String]
      val from = jsonObject.get("from").asInstanceOf[String]
  }

  def handleNackReceipt(jsonObject: Map[String, Object]) {
      val messageId = jsonObject.get("message_id").asInstanceOf[String]
      val from = jsonObject.get("from").asInstanceOf[String]
  }

  def handleControlMessage(jsonObject: Map[String,Object]) {
      val controlType = jsonObject.get("control_type").asInstanceOf[String]
      if ("CONNECTION_DRAINING".equals(controlType)) {
          connectionDraining = true
      } else {
      }
  }

  val connection = connect(senderId, password)

  // Send a sample hello downstream message to a device.
  val toRegId = "APA91bHEEV7AQFtT9NGcOe4zeJf0b-jm2I5TMxb2FX81EcQ1YJbQLFO5tMNFSSO5j95pbVFmq2k6KwCj_h8IDnu8N1TathE00e95iyjqg7bw8pSDqpG0lwbnaNqV2YRQ0ywAw4x9l8LmZrhGJ8NZXDPEItS-nq9HnvHOrR35gqaZKAWvYXppWv4"
  val messageId = nextMessageId()
  val payload = HashMap[String, String]()
  payload.put("Hello", "World")
  payload.put("CCS", "Dummy Message")
  payload.put("EmbeddedMessageId", messageId)
  val collapseKey = "sample"
  val timeToLive = 10000L
  println("Prepared message to send to test client")
  val message = createJsonMessage(toRegId, messageId, payload.toMap, collapseKey, Some(timeToLive), true)
  println("Prepared JSON from message")

  println(s"Connection: ${connection.isConnected}, ${connection.isAuthenticated}")
  sendDownstreamMessage(message)
  println(s"Connection: ${connection.isConnected}, ${connection.isAuthenticated}")

  println("Send sendDownstreamMessage called")
}
