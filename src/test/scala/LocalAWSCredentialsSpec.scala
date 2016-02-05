import java.util.UUID

import akka.http.scaladsl.model.{HttpResponse, HttpEntity}
import akka.stream.ActorMaterializer
import io.dronekit.cloud.utils.AWSCredentials.AWSPermissions
import org.scalatest.{Matchers, FunSpec}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext
import java.io._
import scala.concurrent.duration._
import spray.json._
import DefaultJsonProtocol._

import io.dronekit.cloud.utils.AWSCredentials

import scala.sys.process.Process

/**
 * Created by Adam Villaflor on 2/2/2016.
 */
class LocalAWSCredentialsSpec extends FunSpec with Matchers {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  describe ("should get the credentials") {
    it ("should get credentials from mock environment map") {
      val map = sys.env
      val updated_map = map + ("AWS_ACCESS_KEY_ID" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_ACCESS_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = AWSCredentials.get_credentials_from_map(updated_map, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
      credentials shouldBe testCredentials
    }
    it ("should get credentials from alternate mock environment map") {
      val map = sys.env
      val updated_map = map + ("AWS_ACCESS_KEY" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = AWSCredentials.get_credentials_from_map(updated_map, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
      credentials shouldBe testCredentials
    }
    it ("should get credentials from java system properties") {
      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = AWSCredentials.get_javaSysCredentials()
      credentials shouldBe testCredentials
    }
    it ("should get credentials from a local file") {
      val home = System.getProperty("user.home")
      val uuidFile = home + "\\" + UUID.randomUUID().toString + ".credentials"
      val f = new File(uuidFile)
      val writer = new PrintWriter(f)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = AWSCredentials.get_specific_credentials_profile(uuidFile)
      val credentials = Await.result(futureCredentials, 10 seconds)
      f.delete()
      val testCredentials = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      credentials shouldBe testCredentials
    }
    it ("should get credentials from httpResponse") {
      val entity = HttpEntity("{\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\n\"SecretAccessKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"\n,\"Token\":\"test\"\n}")
      val response = HttpResponse(
        entity = entity
      )
      val testCredentials = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "test"))
      val futureCredentials = AWSCredentials.get_credentials_EC2_response(response)
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials shouldBe testCredentials
    }
    it ("tests ordering of credentials") {
      val home = System.getProperty("user.home")
      val uuidFile = home + "\\" + UUID.randomUUID().toString + ".credentials"
      val f = new File(uuidFile)
      val writer = new PrintWriter(f)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = AWSCredentials.get_credentials(credential_file = uuidFile)
      val credentials2 = Await.result(futureCredentials, 10 seconds)
      val testCredentials2 = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      credentials2 shouldBe testCredentials2
      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials1 = Some(AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials1 = Await.result(AWSCredentials.get_credentials(credential_file = uuidFile), 10 seconds)
      f.delete()
      credentials1 shouldBe testCredentials1
    }
    it ("by going through the whole chain without failing") {
      val futureCredentials2 = AWSCredentials.get_credentials()
      val credentials2 = Await.result(futureCredentials2, 10 seconds)
      credentials2.isEmpty shouldBe false
    }
  }
}
