package example

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing

data class CreateOrder(val orderId: String)
data class OrderCreated(val orderId: String)

@AggregateRoot
class LegacyOrder(private val state: LegacyOrderState) {
    @OnCommand
    fun create(command: CreateOrder): OrderCreated = OrderCreated(command.orderId)
}

class LegacyOrderState {
    var created: Boolean = false
        private set

    @OnSourcing
    fun onCreated(event: OrderCreated) {
        created = true
    }
}
