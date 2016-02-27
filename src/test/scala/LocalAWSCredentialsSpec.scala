import java.io._
import java.util.UUID

import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.ActorMaterializer
import cloud.drdrdr.utils.AWSCredentials
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


/**
 * Created by Adam Villaflor on 2/2/2016.
 */
class LocalAWSCredentialsSpec extends FunSpec with Matchers with AWSCredentials{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  describe ("should get the credentials") {
    it ("should get credentials from mock environment map") {
      val map = sys.env
      val updatedMap = map + ("AWS_ACCESS_KEY_ID" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_ACCESS_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from alternate mock environment map") {
      val map = sys.env
      val updatedMap = map + ("AWS_ACCESS_KEY" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from java system properties") {
      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = AWSCredentials.getJavaSystemCredentials()
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from a local file") {
      val home = new File(System.getProperty("user.home"))
      val uuidFile = new File(home.toPath + File.separator + UUID.randomUUID().toString + ".credentials")
      val writer = new PrintWriter(uuidFile)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = AWSCredentials.getSpecificCredentialsProfile(uuidFile.toString)
      val credentials = Await.result(futureCredentials, 10 seconds)
      uuidFile.delete()
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from httpResponse") {
      val entity = HttpEntity("{\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\n\"SecretAccessKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"\n,\"Token\":\"test\"\n}")
      val response = HttpResponse(
        entity = entity
      )
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "test"))
      val futureCredentials = getCredentialsEC2Response(response)
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("tests ordering of credentials") {
      val home = System.getProperty("user.home")
      val uuidFile = home + File.separator + UUID.randomUUID().toString + ".credentials"
      val f = new File(uuidFile)
      val writer = new PrintWriter(f)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = AWSCredentials.getCredentials(credentialFile = uuidFile)
      val credentials2 = Await.result(futureCredentials, 10 seconds)
      val testCredentials2 = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))

      credentials2.get.accessKeyId shouldBe testCredentials2.get.accessKeyId
      credentials2.get.secretAccessKey shouldBe testCredentials2.get.secretAccessKey
      credentials2.get.token shouldBe testCredentials2.get.token

      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials1 = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials1 = Await.result(AWSCredentials.getCredentials(credentialFile = uuidFile), 10 seconds)
      f.delete()
      credentials1.get.accessKeyId shouldBe testCredentials1.get.accessKeyId
      credentials1.get.secretAccessKey shouldBe testCredentials1.get.secretAccessKey
      credentials1.get.token shouldBe testCredentials1.get.token
    }
    it ("by going through the whole chain without failing") {
      val futureCredentials2 = AWSCredentials.getCredentials()
      val credentials2 = Await.result(futureCredentials2, 10 seconds)
      credentials2.isEmpty shouldBe false
    }
  }
}
