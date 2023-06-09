package mos.quarkus.play;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.startsWith;

@QuarkusTest
public class ExampleResourceTest {

	@Test
	public void testHelloEndpoint() {
		given()
				.when().get("/hello")
				.then()
				.statusCode(200)
				.body(startsWith("Hello World! Quarkus is running."));
	}

}