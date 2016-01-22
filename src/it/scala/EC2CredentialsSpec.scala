import akka.http.scaladsl.model.{StatusCodes, HttpMethods, HttpRequest}
import io.dronekit.cloud.SignRequestForAWS
import io.dronekit.cloud.utils.Config._
import org.scalatest.{Matchers, FunSpec}
import akka.stream.ActorMaterializer

import io.dronekit.cloud.utils.AWSCredentials
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
/**
 * Created by Adam Villaflor on 1/22/2016.
 */
class EC2CredentialsSpec extends FunSpec with Matchers{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  describe("Should get the credentials") {
    it ("get credentials") {
      val futureCredentials = AWSCredentials.get_Amazon_EC2_metadata_credentials("aws-opsworks-ec2-role")
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials.isEmpty shouldBe false
    }
    it ("send a request") {
      val futureCredentials = AWSCredentials.get_Amazon_EC2_metadata_credentials("aws-opsworks-ec2-role")
      Await.result(futureCredentials, 10 seconds) match {
        case Some(permission) =>
          val accessKeyID = permission.accessKeyId
          val kSecret = permission.secretAccessKey
          val baseURI = awsConfig.getString("URI")
          val service = awsConfig.getString("service")
          val region = awsConfig.getString("region")
          val URI = s"${baseURI}?Version=2013-10-15&Action=DescribeRegions"
          val request = HttpRequest(
            method = HttpMethods.GET,
            uri = URI
          )
          val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, service), 15 seconds)
          val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
          response.status shouldBe StatusCodes.OK
        case None =>
          None shouldBe StatusCodes.OK
      }
    }
  }
}