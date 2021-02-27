package com.example.customers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import reactor.core.publisher.Flux;

@SpringBootApplication
public class CustomersApplication {

	public static void main(String[] args) {
		SpringApplication.run(CustomersApplication.class, args);
	}

	private final String [] names = "Jean, Yuxin,Mario,Zhen,Mia,Maria,Dave,Johan,Francoise,Delfino".split(",");

	private final AtomicInteger counter = new AtomicInteger();

	private final Flux<Customer> customers = Flux.fromStream(
			Stream.generate(new Supplier<Customer>() {
				@Override
				public Customer get() {
					var id = counter.incrementAndGet();
					return new Customer(id, names[id % names.length]);
				}
			}))
			.delayElements(Duration.ofSeconds(3));

	@Bean
	Flux<Customer> customers() {
		return this.customers.publish().autoConnect();
	}


}

@Configuration
@RequiredArgsConstructor
class CustomerWebSocketConfiguration {

	private final ObjectMapper objectMapper;

	@SneakyThrows
	private String from(Customer customer) {
		return this.objectMapper.writeValueAsString(customer);
	}

	@Bean
	WebSocketHandler webSocketHandler(Flux<Customer> customerFlux) {
		return webSocketSession -> {
			var map = customerFlux.map(customer -> from(customer))
					.map(webSocketSession::textMessage);
			return webSocketSession.send(map);
		};
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler customerWsh) {
		 return new SimpleUrlHandlerMapping(Map.of("/ws/customers", customerWsh), 10);

	}
}

@RestController
@RequiredArgsConstructor
class CustomerRestController {

	private final Flux<Customer> customerFlux;

	@GetMapping (
			produces = MediaType.TEXT_EVENT_STREAM_VALUE,
			value = "/customers"
	)
	Flux<Customer> get() {
		return this.customerFlux;
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Customer {
	private Integer id;
	private String name;
}

@RestController
class ReliabilityRestController {

	private static final Logger logger = LoggerFactory.getLogger(ReliabilityRestController.class);

	private final Map<String, AtomicInteger> countOfErrors = new ConcurrentHashMap<>();

	private final Map<Long, AtomicInteger> countPerSecond = new ConcurrentHashMap<>();

	@GetMapping("hello")
	String hello() {
		var now = System.currentTimeMillis();
		var second = (long)(now /1000);
		var countForTheCurrentSecond = this.countPerSecond.compute(second, (aLong, atomicInteger) -> {
			if (atomicInteger==null) atomicInteger = new AtomicInteger(0);
			atomicInteger.incrementAndGet();
			return atomicInteger;
		});
		System.out.println("there have been " + countForTheCurrentSecond.get() + " requests for the second " + second);
		return "hello, world";
	}

	@GetMapping("/error/{id}")
	ResponseEntity<?> error(@PathVariable String id) {
		var result = countOfErrors.compute(id, (s, atomicInteger) -> {
			if (null == atomicInteger) atomicInteger = new AtomicInteger(0);
			atomicInteger.incrementAndGet();
			return atomicInteger;
		}).get();

		var log = String.format("for ID %s on count #%s", id, result);
		if (result < 5) {
			logger.info("error " + log);
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		} else {
			logger.info("success " + log);
			var message = String.format("good job, %s, you did it on try #%s", id, result);
			return ResponseEntity.ok(Map.of("message", message));
		}
	}
}