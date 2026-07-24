package kr.ac.unist.bus

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class UnistBusBackendApplication

fun main(args: Array<String>) {
	runApplication<UnistBusBackendApplication>(*args)
}
