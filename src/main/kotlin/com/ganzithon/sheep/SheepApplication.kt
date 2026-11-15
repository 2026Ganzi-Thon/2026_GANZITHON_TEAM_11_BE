package com.ganzithon.sheep

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SheepApplication

fun main(args: Array<String>) {
    runApplication<SheepApplication>(*args)
}
