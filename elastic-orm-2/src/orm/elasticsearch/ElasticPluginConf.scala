package orm.elasticsearch

import java.net.InetAddress
import java.util.concurrent.RejectedExecutionException

import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import webby.api.Application
import webby.mvc.AppStub

trait ElasticPluginConf extends webby.api.Plugin {

  private var _client: Client = _

  val clusterName: String
  val httpHost: InetAddress
  val httpPort: Int
  def transportPort: Int = 9300

  def client: Client = {
    if (_client == null) synchronized {
      if (_client == null) _client = startClient()
    }
    _client
  }

  override def onStart() {
    if (AppStub.isRealProd(app)) _client = startClient()
  }

  override def onStop() {
    if (_client != null) {
      try {
        _client.close()
      } catch {
        case e: RejectedExecutionException => webby.api.Logger(getClass).info("ElasticSearch bug on shutdown: " + e.toString)
      }
    }
  }

  protected def app: Application

  protected def startClient(): Client = {
    TransportClient.builder()
      .settings(Settings.builder().put("cluster.name", clusterName))
      .build()
      .addTransportAddress(new InetSocketTransportAddress(httpHost, transportPort))
  }
}


class ElasticPlugin(override val app: Application) extends ElasticPluginConf {
  val clusterName = app.configuration.getString("elastic.clusterName").getOrElse(sys.error("No elastic.clusterName defined"))
  val httpHost = InetAddress.getByName(app.configuration.getString("elastic.httpHost").getOrElse("127.0.0.1"))
  val httpPort = app.configuration.getInt("elastic.httpPort").getOrElse(9200)
  override val transportPort: Int = app.configuration.getInt("elastic.transportPort").getOrElse(9300)
}
