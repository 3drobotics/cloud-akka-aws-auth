import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cloud.drdrdr.SignRequestForAWS
import cloud.drdrdr.utils.Config.awsConfig
import cloud.drdrdr.utils.{SignRequestForAWSTest, AWSCredentialSource, AWSCredentials, AWSPermissions}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by Adam Villaflor on 1/22/2016.
  *
  */
class EC2CredentialsSpec extends FunSpec with Matchers{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  val requestSigner = new SignRequestForAWSTest

  val credentialProvider = new AWSCredentials()

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
    it("get credentials") {
      val credentialSource = credentialProvider.getAmazonEC2CredentialsSource()
      val credentials = Await.result(credentialSource.getCredentials, 10 seconds)
      credentials.token.isEmpty shouldBe false
      credentials.expiration.isEmpty shouldBe false
    }
    it("send a request") {
      val credentialsSource = credentialProvider.getAmazonEC2CredentialsSource()
      val baseURI = awsConfig.getString("URI")
      val service = awsConfig.getString("service")
      val region = awsConfig.getString("region")
      val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI
      )
      val authRequest = Await.result(requestSigner.addAuthorizationHeaderFromCredentialSource(request, region, service, credentialsSource), 10 seconds)
      val response = Await.result(post(authRequest), 10 seconds)
      response.status shouldBe StatusCodes.OK
    }
    it("send a request using general get method") {
      val credentialsSource = credentialProvider.getCredentials(profile = "fail")
      val permission = Await.result(credentialsSource.getCredentials, 10 seconds)
      val accessKeyID = permission.accessKeyId
      val kSecret = permission.secretAccessKey
      val token = permission.token
      val baseURI = awsConfig.getString("URI")
      val service = awsConfig.getString("service")
      val region = awsConfig.getString("region")
      val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI
      )
      val authRequest = Await.result(requestSigner.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 10 seconds)
      val response = Await.result(post(authRequest), 10 seconds)
      response.status shouldBe StatusCodes.OK
    }
    it("send a request using general get method and credentialsSource") {
      val credentialsSource = credentialProvider.getCredentials(profile = "fail")
      val baseURI = awsConfig.getString("URI")
      val service = awsConfig.getString("service")
      val region = awsConfig.getString("region")
      val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI
      )
      val authRequest = Await.result(requestSigner.addAuthorizationHeaderFromCredentialSource(request, region, service, credentialsSource), 10 seconds)
      val response = Await.result(post(authRequest), 10 seconds)
      response.status shouldBe StatusCodes.OK
    }
  }
  describe("Check expiration") {
    it("check that the credentials update") {
      val entity = HttpEntity("{\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\n\"SecretAccessKey\":\"\"\n,\"Token\":\"test\"\n,\"Expiration\":\"0\"\n }")
      val response = HttpResponse(
        entity = entity
      )
      val futureCredentials = credentialProvider.getCredentialsEC2Response(response)
      val futureCreds = futureCredentials map {
        case Some(credentials: AWSPermissions) =>
          credentials
        case None =>
          new AWSPermissions("", "")
      }
      val credentialsSource = new AWSCredentialSource(futureCreds, credentialProvider)
      val credentials = Await.result(credentialsSource.getCredentials, 10 seconds)
      credentials.accessKeyId.isEmpty shouldBe false
      credentials.secretAccessKey.isEmpty shouldBe false
      // important test because this one should be updated
      credentials.token.isEmpty shouldBe false
      credentials.expiration.isEmpty shouldBe false
    }
  }
}
