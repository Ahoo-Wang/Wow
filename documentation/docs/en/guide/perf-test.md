---
title: Performance Testing
description: Performance benchmarks and test results for the Wow framework under different scenarios.
---

# Performance Testing

- Test Code: [Example](https://github.com/Ahoo-Wang/Wow/tree/main/example)
- Test Scenarios: Add to cart, place order
- Command send wait mode (`WaitPlan`): `SENT`, `PROCESSED`

## Reproducing These Benchmarks

### Infrastructure (Kubernetes + Helm)

The benchmark environment uses Helm values files under
[`deploy/example/perf/`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf):

```bash
# Install infrastructure via Helm (see each file's header comment for the exact command)
helm install mongodb-test bitnami/mongodb-sharded -n test -f deploy/example/perf/mongo.yaml
helm install redis-test bitnami/redis-cluster -n test -f deploy/example/perf/redis.yaml
helm install kafka-test bitnami/kafka -n test -f deploy/example/perf/kafka.yaml --set kraft.enabled=false --version 23.0.5
helm install zookeeper-test bitnami/zookeeper -n test --set global.storageClass="alicloud-disk-essd" --set replicaCount=3 --set persistence.size=20Gi

# Apply the application ConfigMap BEFORE the Deployment (deployment.yaml mounts it)
kubectl apply -f deploy/example/perf/config/mongo_kafka_redis.yaml

# Deploy the Wow example service
kubectl apply -f deploy/example/perf/deployment.yaml
```

The application configuration for the benchmark uses
[`mongo_kafka_redis.yaml`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/config/mongo_kafka_redis.yaml)
— Kafka for the command/event bus, MongoDB for the event store, Redis for the snapshot store
+ PrepareKey + message bus recovery. Alternative configs (`in-memory.yaml`, `redis.yaml`,
`kafka_redis.yaml`) are available for testing different storage topologies.

### Running the Load Test

The `.http` request templates are in the
[`deploy/example/request/`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/request)
directory (`AddCartItem.http`, `CreateOrder.http`). Use a load testing tool (e.g. k6, Gatling,
or JMeter) pointed at the same endpoints with the `Command-Wait-Stage` header set to `SENT`
or `PROCESSED`.

## Deployment Environment

- [Redis](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/redis.yaml)
- [MongoDB](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/mongo.yaml)
- [Kafka](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/kafka.yaml)
- [Application-Config](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/config/mongo_kafka_redis.yaml)
- [Application-Deployment](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/deployment.yaml)

## Performance Test Report

### Add to Cart

```http request
POST {{host}}/cart/{{$uuid}}/add_cart_item
Content-Type: application/json
Command-Wait-Stage: PROCESSED
Command-Wait-Timeout: 30000
Command-Request-Id: {{$uuid}}

{
  "productId": "{{$uuid}}",
  "quantity": 1
}

> {%
    client.test("Request executed successfully", function() {
        client.assert(response.status === 200, "Response status is not 200");
    });
%}

```

- [Detailed Report (PDF)-SENT](../../public/images/perf/Example.Cart.Add@SENT.pdf)
- [Detailed Report (PDF)-PROCESSED](../../public/images/perf/Example.Cart.Add@PROCESSED.pdf)

> With command wait plan (`WaitPlan`) set to `SENT` mode, the add to cart command (`AddCartItem`) write request API underwent 2 minutes of stress testing, with average TPS of *59625*, peak of *82312*, and average response time of *29* milliseconds.

![AddCartItem-SENT](../../public/images/perf/Example.Cart.Add@SENT.png)

> With command wait plan (`WaitPlan`) set to `PROCESSED` mode, the add to cart command (`AddCartItem`) write request API underwent 2 minutes of stress testing, with average TPS of *18696*, peak of *24141*, and average response time of *239* milliseconds.

![AddCartItem-PROCESSED](../../public/images/perf/Example.Cart.Add@PROCESSED.png)

### Place Order

```http request
POST {{host}}/customer/{{$uuid}}/tenant/{{$uuid}}/order
Content-Type: application/json
Command-Wait-Stage: PROCESSED
Command-Wait-Timeout: 30000
Command-Request-Id: {{$uuid}}

{
  "fromCart": false,
  "items": [
    {
      "productId": "{{$uuid}}",
      "price": 10,
      "quantity": 10
    }
  ],
  "address": {
    "country": "china",
    "province": "shanghai",
    "city": "shanghai",
    "district": "huangpu",
    "detail": "renmin road 1000"
  }
}

> {%
    client.test("Request executed successfully", function() {
        client.assert(response.status === 200, "Response status is not 200");
    });
%}
```

- [Detailed Report (PDF)-SENT](../../public/images/perf/Example.Order.Create@SENT.pdf)
- [Detailed Report (PDF)-PROCESSED](../../public/images/perf/Example.Order.Create@PROCESSED.pdf)

> With command wait plan (`WaitPlan`) set to `SENT` mode, the place order command (`CreateOrder`) write request API underwent 2 minutes of stress testing, with average TPS of *47838*, peak of *86200*, and average response time of *217* milliseconds.

![CreateOrder-SENT](../../public/images/perf/Example.Order.Create@SENT.png)

> With command wait plan (`WaitPlan`) set to `PROCESSED` mode, the place order command (`CreateOrder`) write request API underwent 2 minutes of stress testing, with average TPS of *18230*, peak of *25506*, and average response time of *268* milliseconds.

![CreateOrder-PROCESSED](../../public/images/perf/Example.Order.Create@PROCESSED.png)
