```kotlin
class CartTest : AggregateSpec<Cart, CartState>({
    tenantId(tenantId)
    aggregateId(aggregateId)
    ownerId(ownerId)
    eventStore(eventStore)
    serviceProvider(serviceProvider)
    inject {
        
    }
    given() {
        inject {

        }
        givenOwnerId(ownerId)
        whenCommand(command1) {
            expectNoError()
            expectEventType(eventType)
            whenCommand(command1_1) {
                expectNoError()
            }
            whenCommand(command1_2) {
                expectNoError()
            }
        }
        whenCommand(command2) {

        }
    }
    given() {
        givenEvent(event) {
            whenCommand(command1) {
                expectNoError()
                expectEventType(eventType)
                whenCommand(command1_1) {
                    
                }
            }
            whenCommand(command2) {
                
            }
        }
    }

    given() {
        givenState(state) {
            whenCommand(command1) {
                expectNoError()
                expectEventType(eventType)
            }
            whenCommand(command2) {

            }
        }
    }
})
```