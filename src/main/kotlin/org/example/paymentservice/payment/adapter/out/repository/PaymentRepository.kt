package org.example.paymentservice.payment.adapter.out.repository

import org.example.paymentservice.payment.domain.PaymentEvent
import reactor.core.publisher.Mono

interface PaymentRepository {
    fun save(paymentEvent: PaymentEvent): Mono<Void>
}