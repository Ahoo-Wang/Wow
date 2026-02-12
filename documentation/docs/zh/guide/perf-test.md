# 性能评测

- 测试代码：[Example](https://github.com/Ahoo-Wang/Wow/tree/main/example)
- 测试场景：加入购物车、下单
- 命令发送等待模式（`WaitStrategy`）：`SENT`、`PROCESSED`

## 部署环境

- [Redis](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/redis.yaml)
- [MongoDB](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/mongo.yaml)
- [Kafka](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/kafka.yaml)
- [Application-Config](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/config/mongo_kafka_redis.yaml)
- [Application-Deployment](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/example/perf/deployment.yaml)

## 压测报告

### 加入购物车

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

- [详细报告(PDF)-SENT](../../public/images/perf/Example.Cart.Add@SENT.pdf)
- [详细报告(PDF)-PROCESSED](../../public/images/perf/Example.Cart.Add@PROCESSED.pdf)

> 命令等待策略（`WaitStrategy`）为`SENT`模式，加入购物车命令（`AddCartItem`）写请求 API 经过 2 分钟的压测，平均 TPS 为 *59625*，峰值为 *82312*，平均响应时间为 *29* 毫秒。

![AddCartItem-SENT](../../public/images/perf/Example.Cart.Add@SENT.png)

> 命令等待策略（`WaitStrategy`）为`PROCESSED`模式，加入购物车命令（`AddCartItem`）写请求 API 经过 2 分钟的压测，平均 TPS 为 *18696*，峰值为 *24141*，平均响应时间为 *239* 毫秒。

![AddCartItem-PROCESSED](../../public/images/perf/Example.Cart.Add@PROCESSED.png)

### 下单

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

- [详细报告(PDF)-SENT](../../public/images/perf/Example.Order.Create@SENT.pdf)
- [详细报告(PDF)-PROCESSED](../../public/images/perf/Example.Order.Create@PROCESSED.pdf)

> 命令等待策略（`WaitStrategy`）为`SENT`模式，下单命令（`CreateOrder`）写请求 API 经过 2 分钟的压测，平均 TPS 为 *47838*，峰值为 *86200*，平均响应时间为 *217* 毫秒。

![CreateOrder-SENT](../../public/images/perf/Example.Order.Create@SENT.png)

> 命令等待策略（`WaitStrategy`）为`PROCESSED`模式，下单命令（`CreateOrder`）写请求 API 经过 2 分钟的压测，平均 TPS 为 *18230*，峰值为 *25506*，平均响应时间为 *268* 毫秒。

![CreateOrder-PROCESSED](../../public/images/perf/Example.Order.Create@PROCESSED.png)
