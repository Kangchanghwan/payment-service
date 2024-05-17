package org.example.paymentservice.payment.adapter.out.persistent.repository

import org.example.paymentservice.payment.adapter.out.persistent.util.MySQLDateTimeFormatter
import org.example.paymentservice.payment.domain.PaymentEvent
import org.example.paymentservice.payment.domain.PaymentStatus
import org.example.paymentservice.payment.domain.PendingPaymentEvent
import org.example.paymentservice.payment.domain.PendingPaymentOrder
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDateTime

@Repository
class R2DBCPaymentRepository(
    private val databaseClient: DatabaseClient,
    private val transactionOperators: TransactionalOperator
) : PaymentRepository {
    override fun save(paymentEvent: PaymentEvent): Mono<Void> {
        return insertPaymentEvent(paymentEvent) // 결제 이벤트를 저장합니다.
            .flatMap { selectPaymentEventId() } // 마지막으로 생성된 이벤트 ID 값을 가져옵니다.
            .flatMap { paymentEventId -> insertPaymentOrders(paymentEvent, paymentEventId) } // 결제 이벤트 아이디를 외래키로 가진 결제주문 건을 생성합니다.
            .`as` { transactionOperators.transactional(it) } // 트랜젝션을 묶는 역할
            .then()
    }

    override fun getPendingPayments(): Flux<PendingPaymentEvent> {
       return databaseClient.sql(SELECT_PENDING_PAYMENT_QUERY)
            .bind("updatedAt", LocalDateTime.now().format(MySQLDateTimeFormatter))
            .fetch()
            .all()
            .groupBy { it["payment_event_id"] as Long }
            .flatMap { groupFlux ->
                groupFlux.collectList().map { results ->
                    PendingPaymentEvent(
                        paymentEventId = groupFlux.key(),
                        paymentKey = results.first()["payment_key"] as String,
                        orderId = results.first()["order_id"] as String,
                        pendingPaymentOrders = results.map { result ->
                            PendingPaymentOrder(
                                paymentOrderId = result["payment_order_id"] as Long,
                                status = PaymentStatus.get(result["payment_order_status"] as String),
                                amount = (result["amount"] as BigDecimal).toLong(),
                                failCount = result["failed_count"] as Byte,
                                threshold = result["threshold"] as Byte
                            )
                        }
                    )
                }
            }
    }

    private fun insertPaymentEvent(paymentEvent: PaymentEvent): Mono<Long> {
        return databaseClient.sql(INSERT_PAYMENT_EVENT_QUERY)
            .bind("buyerId", paymentEvent.buyerId)
            .bind("orderName", paymentEvent.orderName)
            .bind("orderId", paymentEvent.orderId)
            .fetch()
            .rowsUpdated()
    }

    private fun selectPaymentEventId() = databaseClient.sql(LAST_INSERT_ID_QUERY)
        .fetch().first().map {( it["LAST_INSERT_ID()"] as BigInteger).toLong()}


    private fun insertPaymentOrders(
        paymentEvent: PaymentEvent,
        paymentEventId: Long
    ): Mono<Long> {
        val valueClause = paymentEvent.paymentOrders.joinToString(", ") { paymentOrder ->
            "($paymentEventId, ${paymentOrder.sellerId}, '${paymentOrder.orderId}', ${paymentOrder.productId}, ${paymentOrder.amount}, '${paymentOrder.paymentStatus}')"
        }
        return databaseClient.sql(INSERT_PAYMENT_ORDER_QUERY(valueClause))
            .fetch()
            .rowsUpdated()
    }

    companion object {
        val INSERT_PAYMENT_EVENT_QUERY = """
            INSERT INTO payment_events(buyer_id, order_name, order_id)
            VALUES (:buyerId, :orderName, :orderId)
        """.trimIndent()

        val LAST_INSERT_ID_QUERY = """
            SELECT LAST_INSERT_ID() 
        """.trimIndent() // LAST_INSERT_ID()는 MySQL에서 사용되는 함수이며

        val INSERT_PAYMENT_ORDER_QUERY = fun(valueClauses: String) = """
            INSERT INTO payment_orders(payment_event_id, seller_id, order_id, product_id, amount, payment_order_status)
            VALUES $valueClauses
        """.trimIndent()

        val SELECT_PENDING_PAYMENT_QUERY = """
            SELECT pe.id as payment_event_id, pe.payment_key, pe.order_id, po.id as payment_order_id, po.payment_order_status, po.amount, po.failed_count, po.threshold
            FROM payment_events pe
            INNER JOIN payment_orders po on pe.order_id = po.order_id
            WHERE (po.payment_order_status = 'UNKNOWN' OR (po.payment_order_status = 'EXECUTING' AND  po.updated_at <= :updatedAt - INTERVAL 3 MINUTE))
            AND po.failed_count < po.threshold
            LIMIT 10
        """.trimIndent()
    }
}