import java.io._
import java.util.UUID

import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.ActorMaterializer
import cloud.drdrdr.utils.{AWSCredentials, AWSPermissions}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


/**
  * Created by Adam Villaflor on 2/2/2016.
  *
  */
class LocalAWSCredentialsSpec extends FunSpec with Matchers {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  val credentialProvider = new AWSCredentials()

  describe("should get the credentials") {
    it("should get credentials from mock environment map") {
      val map = sys.env
      val updatedMap = map +("AWS_ACCESS_KEY_ID" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_ACCESS_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = credentialProvider.getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
      credentials.get.expiration shouldBe testCredentials.get.expiration
    }
    it("should get credentials from alternate mock environment map") {
      val map = sys.env
      val updatedMap = map +("AWS_ACCESS_KEY" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = credentialProvider.getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
      credentials.get.expiration shouldBe testCredentials.get.expiration
    }
    it("should get credentials from java system properties") {
      val properties = System.getProperties
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = credentialProvider.getJavaSystemCredentials()
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
      credentials.get.expiration shouldBe testCredentials.get.expiration
    }
    it("should get credentials from a local file") {
      val home = new File(System.getProperty("user.home"))
      val uuidFile = new File(home.toPath + File.separator + UUID.randomUUID().toString + ".credentials")
      val writer = new PrintWriter(uuidFile)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = credentialProvider.getSpecificCredentialsProfile(uuidFile.toString)
      val credentials = Await.result(futureCredentials, 10 seconds)
      uuidFile.delete()
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
      credentials.get.expiration shouldBe testCredentials.get.expiration
    }
    it("should get credentials from httpResponse") {
      val entity = HttpEntity("{\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\n\"SecretAccessKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"\n,\"Token\":\"test\"\n,\"Expiration\":\"test\"\n }")
      val response = HttpResponse(
        entity = entity
      )
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "test", "test"))
      val futureCredentials = credentialProvider.getCredentialsEC2Response(response)
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
      credentials.get.expiration shouldBe testCredentials.get.expiration
    }
    it("tests ordering of credentials") {
      val home = System.getProperty("user.home")
      val uuidFile = home + File.separator + UUID.randomUUID().toString + ".credentials"
      val f = new File(uuidFile)
      val writer = new PrintWriter(f)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val credentialsSource = credentialProvider.getCredentials(credentialFile = Some(uuidFile))
      val credentials2 = Await.result(credentialsSource.getCredentials, 10 seconds)
      val testCredentials2 = new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")

      credentials2.accessKeyId shouldBe testCredentials2.accessKeyId
      credentials2.secretAccessKey shouldBe testCredentials2.secretAccessKey
      credentials2.token shouldBe testCredentials2.token
      credentials2.expiration shouldBe testCredentials2.expiration

      val properties = System.getProperties
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials1 = new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val credentials1 = Await.result(credentialProvider.getCredentials(credentialFile = Some(uuidFile)).getCredentials, 10 seconds)
      f.delete()
      credentials1.accessKeyId shouldBe testCredentials1.accessKeyId
      credentials1.secretAccessKey shouldBe testCredentials1.secretAccessKey
      credentials1.token shouldBe testCredentials1.token
      credentials1.expiration shouldBe testCredentials1.expiration
    }
  }
}
