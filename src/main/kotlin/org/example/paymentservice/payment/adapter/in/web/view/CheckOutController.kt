package org.example.paymentservice.payment.adapter.`in`.web.view

import org.example.paymentservice.common.WebAdapter
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import reactor.core.publisher.Mono

@Controller
@WebAdapter
class CheckOutController {

    @GetMapping("/")
    fun checkOut(): Mono<String> {
        return Mono.just("checkout")
    }
}