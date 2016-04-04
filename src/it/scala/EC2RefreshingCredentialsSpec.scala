import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Source, Sink}
import cloud.drdrdr.utils.AWSCredentials.AWSPermissions
import org.scalatest.{Matchers, FunSpec}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cloud.drdrdr.SignRequestForAWS
import cloud.drdrdr.utils.AWSCredentials
import cloud.drdrdr.utils.Config.awsConfig
import org.scalatest.{FunSpec, Matchers}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Future, Await, ExecutionContext}
/**
 * Created by Adam Villaflor on 4/4/2016.
 */
class EC2RefreshingCredentialsSpec extends FunSpec with Matchers with SignRequestForAWS with AWSCredentials{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  //sends outgoing request
  private def post(httpRequest: HttpRequest): Future[HttpResponse] = {
    val endpoint = httpRequest.uri.toString()
    val uri = java.net.URI.create(endpoint)
    val outgoingConn = if (uri.getScheme == "https") {
      Http().outgoingConnectionHttps(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
    } else {
      Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
    }
    Source.single(httpRequest).via(outgoingConn).runWith(Sink.head)
  }

  describe("Should") {
    val credentialSource = AWSCredentials.getAmazonEC2CredentialsSource()
    it("get credentials") {
      val credentials = Await.result(credentialSource.getCredentials, 10 seconds)
      credentials.token.isEmpty shouldBe false
      credentials.expiration.isEmpty shouldBe false
    }
    it("should wait until credentials expire then try to access them to see if they have refreshed") {
      //      sleep until expire
      import java.io._
      val file = new File("./EC2RefreshingCredentialsSpecLog.txt")
      val fw = new FileWriter(file)
      var totalTime = 0
      val time: Int = 300000
      val baseURI = awsConfig.getString("URI")
      val service = awsConfig.getString("service")
      val region = awsConfig.getString("region")
      val URI = s"${baseURI}?Version=2013-10-15&Action=DescribeRegions"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI
      )
      var futureCredentials = credentialSource.getCredentials
      var credentials = Await.result(futureCredentials, 10 seconds)
      val expires = credentials.expiration
      while (totalTime < 130000000) {
        futureCredentials = credentialSource.getCredentials
        credentials = Await.result(futureCredentials, 10 seconds)
        Thread.sleep(time)
        val expiration = credentials.expiration
        if (expiration != expires)
          println("Credentials have been refreshed! after " + totalTime + " milliseconds!")
        println(expiration)
        fw.write(totalTime + ": " +  expiration + "\n")
        totalTime += time
      }
      fw.close()
      val authRequest = Await.result(addAuthorizationHeaderFromCredentialsSource(request, region, service, credentialSource), 10 seconds)
      val response = Await.result(post(authRequest), 10 seconds)
      response.status shouldBe StatusCodes.OK
    }
  }
}
