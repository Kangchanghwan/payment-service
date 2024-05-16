package org.example.paymentservice.payment.application.service

import io.netty.handler.timeout.TimeoutException
import org.example.paymentservice.common.UseCase
import org.example.paymentservice.payment.adapter.out.persistent.exception.PaymentAlreadyProcessedException
import org.example.paymentservice.payment.adapter.out.persistent.exception.PaymentValidationException
import org.example.paymentservice.payment.adapter.out.web.toss.exception.PSPConfirmationException
import org.example.paymentservice.payment.application.port.`in`.PaymentConfirmCommand
import org.example.paymentservice.payment.application.port.`in`.PaymentConfirmUseCase
import org.example.paymentservice.payment.application.port.out.PaymentExecutionPort
import org.example.paymentservice.payment.application.port.out.PaymentStatusUpdateCommand
import org.example.paymentservice.payment.application.port.out.PaymentStatusUpdatePort
import org.example.paymentservice.payment.application.port.out.PaymentValidationPort
import org.example.paymentservice.payment.domain.PaymentConfirmationResult
import org.example.paymentservice.payment.domain.PaymentFailure
import org.example.paymentservice.payment.domain.PaymentStatus
import reactor.core.publisher.Mono

@UseCase
class PaymentConfirmService(
    private val paymentStatusUpdatePort: PaymentStatusUpdatePort,
    private val paymentValidationPort: PaymentValidationPort,
    private val paymentExecutionPort: PaymentExecutionPort,
) : PaymentConfirmUseCase {

    override fun confirm(command: PaymentConfirmCommand): Mono<PaymentConfirmationResult> {
        return paymentStatusUpdatePort.updatePaymentStatusToExecuting(command.orderId, command.paymentKey)
            .filterWhen { paymentValidationPort.isValid(command.orderId, command.amount) }
            .flatMap { paymentExecutionPort.execute(command) }
            .flatMap {
                paymentStatusUpdatePort.updatePaymentStatus(
                    PaymentStatusUpdateCommand(
                        paymentKey = it.paymentKey,
                        orderId = it.orderId,
                        status = it.paymentStatus(),
                        extraDetails = it.extraDetails,
                        failure = it.failure
                    )
                ).thenReturn(it)
            }.map {
                PaymentConfirmationResult(
                    status = it.paymentStatus(),
                    failure = it.failure
                )
            }.onErrorResume { handlePaymentError(it, command) }
    }

    private fun handlePaymentError(
        error: Throwable,
        command: PaymentConfirmCommand
    ): Mono<PaymentConfirmationResult> {
        val (status, failure) = when (error) {
            is PSPConfirmationException -> Pair(
                error.paymentStatus(),
                PaymentFailure(error.errorCode, error.errorMessage)
            )

            is PaymentValidationException -> Pair(
                PaymentStatus.FAILURE,
                PaymentFailure(error::class.simpleName ?: "", error.message ?: "")
            )

            is PaymentAlreadyProcessedException ->
                return Mono.just(
                    PaymentConfirmationResult(
                        status = error.status,
                        PaymentFailure(error::class.simpleName ?: "", error.message ?: "")
                    )
                )

            is TimeoutException -> Pair(
                PaymentStatus.UNKNOWN,
                PaymentFailure(error::class.simpleName ?: "", error.message ?: "")
            )

            else -> Pair(
                PaymentStatus.UNKNOWN,
                PaymentFailure(error::class.simpleName ?: "", error.message ?: "")
            )
        }

        val paymentStatusUpdateCommand = PaymentStatusUpdateCommand(
            paymentKey = command.paymentKey,
            orderId = command.orderId,
            status = status,
            failure = failure
        )
        return paymentStatusUpdatePort.updatePaymentStatus(paymentStatusUpdateCommand)
            .map { PaymentConfirmationResult(status, failure) }
    }
}