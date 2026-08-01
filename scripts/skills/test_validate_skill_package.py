import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import validate_skill_package as validator
from validate_skill_package import (
    ValidationError,
    load_agent_metadata,
    load_eval_document,
    precheck_output,
    validate_agent_metadata,
    validate_eval_document,
    validate_plugins,
)


class ValidateSkillPackageTest(unittest.TestCase):
    REPO_ROOT = Path(__file__).resolve().parents[2]

    def test_accepts_valid_agent_metadata(self):
        validate_agent_metadata(
            "wow",
            {
                "interface": {
                    "display_name": "Wow Framework Router",
                    "short_description": "Route mixed Wow work and look up APIs",
                    "default_prompt": "Use $wow to route this mixed Wow task.",
                }
            },
        )

    def test_loads_generated_agent_metadata_without_external_yaml_parser(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "openai.yaml"
            path.write_text(
                'interface:\n'
                '  display_name: "Wow Framework Router"\n'
                '  short_description: "Route mixed Wow work and look up APIs"\n'
                '  default_prompt: "Use $wow to route this task."\n'
            )

            document = load_agent_metadata(path)

            self.assertEqual("Wow Framework Router", document["interface"]["display_name"])
            self.assertEqual("Use $wow to route this task.", document["interface"]["default_prompt"])

    def test_rejects_unparsed_agent_metadata_content(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "openai.yaml"
            path.write_text(
                'interface:\n'
                '  display_name: "Wow Framework Router"\n'
                '  short_description: "Route mixed Wow work and look up APIs"\n'
                '  default_prompt: "Use $wow to route this task."\n'
                'this is not valid yaml\n'
            )

            with self.assertRaisesRegex(ValidationError, "unsupported or invalid"):
                load_agent_metadata(path)

    def test_agent_prompt_does_not_read_skill_name_from_yaml_comment(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "openai.yaml"
            path.write_text(
                "interface:\n"
                "  display_name: 'Wow Framework Router'\n"
                "  short_description: 'Route mixed Wow work and look up APIs'\n"
                "  default_prompt: 'Route mixed Wow work.' # mention $wow'\n"
            )

            document = load_agent_metadata(path)

            self.assertEqual("Route mixed Wow work.", document["interface"]["default_prompt"])
            with self.assertRaisesRegex(ValidationError, r"\$wow"):
                validate_agent_metadata("wow", document)

    def test_rejects_agent_prompt_without_skill_name(self):
        with self.assertRaisesRegex(ValidationError, r"\$wow"):
            validate_agent_metadata(
                "wow",
                {
                    "interface": {
                        "display_name": "Wow Framework Router",
                        "short_description": "Route mixed Wow work and look up APIs",
                        "default_prompt": "Route this mixed Wow task.",
                    }
                },
            )

    def test_rejects_agent_prompt_with_longer_prefixed_skill_name(self):
        for skill_token in ("$wow-debugging", "$wow2", "$wow_extra"):
            with self.subTest(skill_token=skill_token):
                with self.assertRaisesRegex(ValidationError, r"\$wow"):
                    validate_agent_metadata(
                        "wow",
                        {
                            "interface": {
                                "display_name": "Wow Framework Router",
                                "short_description": "Route mixed Wow work and look up APIs",
                                "default_prompt": f"Use {skill_token} for this task.",
                            }
                        },
                    )

    def test_rejects_duplicate_eval_ids(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            eval_case = self.valid_eval_case()
            document = self.valid_eval_document([eval_case, eval_case])

            with self.assertRaisesRegex(ValidationError, "duplicate eval id"):
                validate_eval_document(document, repo_root)

    def test_rejects_eval_without_rubric(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            eval_case = self.valid_eval_case()
            eval_case["rubric"] = []

            with self.assertRaisesRegex(ValidationError, "rubric"):
                validate_eval_document(self.valid_eval_document([eval_case]), repo_root)

    def test_rejects_missing_eval_source_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)

            with self.assertRaisesRegex(ValidationError, "source.kt"):
                validate_eval_document(
                    self.valid_eval_document([self.valid_eval_case()]),
                    repo_root,
                )

    def test_rejects_absolute_eval_source_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            source = repo_root / "source.kt"
            source.write_text("class Source")
            eval_case = self.valid_eval_case()
            eval_case["source_refs"] = [str(source)]

            with self.assertRaisesRegex(ValidationError, "relative"):
                validate_eval_document(self.valid_eval_document([eval_case]), repo_root)

    def test_rejects_eval_without_output_checks(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            eval_case = self.valid_eval_case()
            eval_case["output_checks"] = {
                "must_contain": [],
                "must_not_contain": [],
            }

            with self.assertRaisesRegex(ValidationError, "at least one check"):
                validate_eval_document(self.valid_eval_document([eval_case]), repo_root)

    def test_rejects_routing_prompt_that_leaks_skill_name(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            document = self.valid_eval_document([self.valid_eval_case()])
            document["routing_cases"][0]["prompt"] = "Use $wow to implement this."

            with self.assertRaisesRegex(ValidationError, "must not reveal"):
                validate_eval_document(document, repo_root)

    def test_rejects_routing_prompt_that_leaks_bare_specialist_name(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            document = self.valid_eval_document([self.valid_eval_case()])
            document["routing_cases"][0]["prompt"] = (
                "Explicitly use wow-debugging for this task."
            )

            with self.assertRaisesRegex(ValidationError, "must not reveal"):
                validate_eval_document(document, repo_root)

    def test_rejects_negative_routing_case_with_positive_target(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            document = self.valid_eval_document([self.valid_eval_case()])
            document["routing_cases"][2]["route_expectation"] = {
                "eval_target": "simple-aggregation-sourcing"
            }

            with self.assertRaisesRegex(ValidationError, "negative case"):
                validate_eval_document(document, repo_root)

    def test_rejects_non_boolean_negative_route_expectation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            document = self.valid_eval_document([self.valid_eval_case()])
            document["routing_cases"][2]["route_expectation"] = {"none": 1}

            with self.assertRaisesRegex(ValidationError, "none=true"):
                validate_eval_document(document, repo_root)

    def test_mixed_routing_case_requires_skill_sequence(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "source.kt").write_text("class Source")
            document = self.valid_eval_document([self.valid_eval_case()])
            document["routing_cases"][1]["route_expectation"].pop("skill_sequence")

            with self.assertRaisesRegex(ValidationError, "skill_sequence"):
                validate_eval_document(document, repo_root)

    def test_rejects_invalid_min_occurrences(self):
        for invalid_value in (0, -1, True, "2"):
            with self.subTest(invalid_value=invalid_value), tempfile.TemporaryDirectory() as temp_dir:
                repo_root = Path(temp_dir)
                (repo_root / "source.kt").write_text("class Source")
                eval_case = self.valid_eval_case()
                eval_case["output_checks"]["must_contain"][0]["min_occurrences"] = invalid_value

                with self.assertRaisesRegex(ValidationError, "min_occurrences"):
                    validate_eval_document(self.valid_eval_document([eval_case]), repo_root)

    def test_runs_literal_output_prechecks(self):
        eval_case = self.valid_eval_case()
        eval_case["output_checks"] = {
            "must_contain": [
                {"id": "sourcing-handler", "any_of": ["@OnSourcing", "fun onSourcing("]}
            ],
            "must_not_contain": [
                {"id": "wrong-prepare-value", "any_of": ["PrepareKey<String>"]}
            ],
        }

        self.assertEqual(
            [],
            precheck_output(
                eval_case,
                "@OnSourcing\nfun onCartItemAdded(event: CartItemAdded) {}",
            ),
        )
        failures = precheck_output(eval_case, "fun onCartItemAdded(event: CartItemAdded) {}")
        self.assertEqual(1, len(failures))
        self.assertIn("@OnSourcing", failures[0])
        failures = precheck_output(
            eval_case,
            "@OnSourcing\ninterface UsernamePrepare : PrepareKey<String>",
        )
        self.assertEqual(1, len(failures))
        self.assertIn("forbidden content", failures[0])

    def test_kotlin_handler_discovery_accepts_qualified_annotation_and_parameter_annotation(self):
        code = (
            "@me.ahoo.wow.api.annotation.OnEvent\n"
            "private fun paid(\n"
            "    @Suppress(\"UNUSED_PARAMETER\") event: DomainEvent<OrderPaid>,\n"
            ") = Unit"
        )

        self.assertEqual(
            1,
            validator.count_discoverable_kotlin_handlers(
                code,
                {
                    "annotation": "OnEvent",
                    "default_name": "onEvent",
                    "parameter_type": "OrderPaid",
                },
            ),
        )

    def test_saga_precheck_requires_every_derived_command(self):
        eval_case = self.actual_eval("stateless-transfer-saga")

        failures = precheck_output(
            eval_case,
            "@StatelessSaga\nfun prepared() = Entry(\"target\")\nfun failed() = UnlockAmount(\"source\")",
        )

        self.assertTrue(any("confirm-command" in failure for failure in failures))

    def test_saga_precheck_requires_discoverable_handlers(self):
        eval_case = self.actual_eval("stateless-transfer-saga")
        output = (
            "```kotlin\n@StatelessSaga\n"
            "fun handlePrepared() = Entry(\"target\")\n"
            "fun handleAmountEntered() = Confirm(\"source\")\n"
            "fun handleEntryFailed() = UnlockAmount(\"source\")\n```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("discoverable-event-handlers" in failure for failure in failures))

    def test_saga_precheck_does_not_double_count_one_annotated_handler(self):
        eval_case = self.actual_eval("stateless-transfer-saga")
        output = (
            "```kotlin\n@StatelessSaga\n@OnEvent\n"
            "fun onEvent(event: Any) = listOf(Entry(\"target\"), Confirm(\"source\"), "
            "UnlockAmount(\"source\"))\n```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("discoverable-event-handlers" in failure for failure in failures))

    def test_saga_precheck_does_not_double_count_two_handlers(self):
        eval_case = self.actual_eval("stateless-transfer-saga")
        output = (
            "```kotlin\n@StatelessSaga\n@OnEvent\n"
            "fun onEvent(event: Prepared) = Entry(\"target\")\n"
            "fun onEvent(event: Any) = listOf(Confirm(\"source\"), UnlockAmount(\"source\"))\n```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("discoverable-event-handlers" in failure for failure in failures))

    def test_simple_aggregation_precheck_requires_all_requested_events(self):
        eval_case = self.actual_eval("simple-aggregation-sourcing")

        failures = precheck_output(
            eval_case,
            "@AggregateRoot\n@OnSourcing\nCartItemAdded(productId, quantity)",
        )

        self.assertTrue(any("quantity-event" in failure for failure in failures))
        self.assertTrue(any("remove-event" in failure for failure in failures))

    def test_simple_aggregation_precheck_requires_discoverable_command_handlers(self):
        eval_case = self.actual_eval("simple-aggregation-sourcing")
        output = (
            "```kotlin\n@AggregateRoot\n@OnSourcing\n"
            "fun addItem() = CartItemAdded(productId, quantity)\n"
            "fun removeItem() = CartItemRemoved(productId)\n"
            "val changed = CartQuantityChanged(productId, quantity)\n```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("discoverable-command-handlers" in failure for failure in failures))

    def test_simple_aggregation_precheck_does_not_double_count_one_annotated_handler(self):
        eval_case = self.actual_eval("simple-aggregation-sourcing")
        output = (
            "```kotlin\n@AggregateRoot\n@OnCommand\n"
            "fun onCommand(command: Any) = listOf(CartItemAdded(), CartQuantityChanged(), "
            "CartItemRemoved())\n@OnSourcing fun apply(event: Any) = Unit\n```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("discoverable-command-handlers" in failure for failure in failures))

    def test_simple_aggregation_precheck_accepts_multiline_annotation_and_private_handler(self):
        eval_case = self.actual_eval("simple-aggregation-sourcing")
        output = (
            "```kotlin\n@AggregateRoot class Cart {\n"
            "@OnCommand(\n  returns = [CartItemAdded::class, CartQuantityChanged::class],\n)\n"
            "private fun add(command: AddCartItem) = CartItemAdded(command.productId)\n"
            "private fun onCommand(command: RemoveCartItem) = CartItemRemoved(command.productId)\n"
            "}\n@OnSourcing fun apply(event: CartItemAdded) = Unit\n"
            "val changed = CartQuantityChanged(productId)\n```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_query_precheck_requires_filters_and_pagination(self):
        eval_case = self.actual_eval("tenant-paged-query")

        failures = precheck_output(
            eval_case,
            'pagedQuery { sort { "createdAt".desc() }; projection { include("id", "status", "totalAmount") } }',
        )

        self.assertTrue(any("tenant-filter" in failure for failure in failures))
        self.assertTrue(any("active-status" in failure for failure in failures))
        self.assertTrue(any("pagination" in failure for failure in failures))

    def test_query_precheck_rejects_prose_token_bag(self):
        eval_case = self.actual_eval("tenant-paged-query")
        output = (
            "Explain condition tenantId ACTIVE pagination index size createdAt.desc() "
            'projection include("id", "status", "totalAmount").'
        )

        self.assertTrue(precheck_output(eval_case, output))

    def test_cart_spec_precheck_rejects_prose_token_bag(self):
        eval_case = self.actual_eval("cart-aggregate-spec")
        output = (
            "Describe AggregateSpec<Cart, CartState>, fork( branches, expectState { checks, "
            "CartItemAdded, CartQuantityChanged, CartItemRemoved, and expectErrorType."
        )

        self.assertTrue(precheck_output(eval_case, output))

    def test_query_precheck_accepts_split_projection_includes(self):
        eval_case = self.actual_eval("tenant-paged-query")
        output = (
            '```kotlin\npagedQuery { condition { tenantId(tenantId); "status" eq "ACTIVE" }; '
            'pagination { index(1); size(20) }; sort { "createdAt".desc() }; '
            'projection { include("id"); include("status"); include("totalAmount") } }\n```'
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_query_precheck_requires_every_projection_field(self):
        eval_case = self.actual_eval("tenant-paged-query")
        output = (
            'pagedQuery { condition { tenantId(tenantId); "status" eq "ACTIVE" }; '
            'pagination { index(1); size(20) }; sort { "createdAt".desc() }; '
            'projection { include("id"); include("status") } }'
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("projection-total-amount" in failure for failure in failures))

    def test_storage_precheck_requires_both_redis_channels(self):
        eval_case = self.actual_eval("aggregate-storage-routing")

        failures = precheck_output(eval_case, "storage-routing:\n  hot_aggregate:")

        self.assertTrue(any("event-channel" in failure for failure in failures))
        self.assertTrue(any("snapshot-channel" in failure for failure in failures))
        self.assertTrue(any("redis-storage" in failure for failure in failures))

        one_redis = (
            "storage-routing:\n"
            "  hot_aggregate:\n"
            "    event:\n"
            "      storage: redis\n"
            "    snapshot:\n"
            "      binding: snapshots"
        )
        failures = precheck_output(eval_case, one_redis)
        self.assertTrue(any("redis-storage" in failure for failure in failures))

    def test_storage_precheck_requires_full_configuration_root(self):
        eval_case = self.actual_eval("aggregate-storage-routing")
        wrong_root = (
            "The valid prefix is wow.eventsourcing.storage-routing:\n"
            "wrong:\n"
            "  eventsourcing:\n"
            "    storage-routing:\n"
            "      aggregates:\n"
            "        hot_aggregate:\n"
            "          event:\n"
            "            storage: redis\n"
            "          snapshot:\n"
            "            storage: redis"
        )

        failures = precheck_output(eval_case, wrong_root)

        self.assertTrue(any("routing-tree" in failure for failure in failures))

    def test_storage_precheck_accepts_dotted_configuration_root(self):
        eval_case = self.actual_eval("aggregate-storage-routing")
        output = (
            "```yaml\n"
            "wow.eventsourcing.storage-routing:\n"
            "  aggregates:\n"
            "    hot_aggregate:\n"
            "      event:\n"
            "        storage: redis\n"
            "      snapshot:\n"
            "        storage: redis\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_storage_precheck_accepts_unrelated_sibling_configuration(self):
        eval_case = self.actual_eval("aggregate-storage-routing")
        output = (
            "```yaml\n"
            "wow:\n"
            "  redis:\n"
            "    enabled: true\n\n"
            "  eventsourcing:\n"
            "    snapshot:\n"
            "      enabled: true\n\n"
            "    storage-routing:\n"
            "      aggregates:\n"
            "        hot_aggregate:\n"
            "          event:\n"
            "            storage: redis\n"
            "          snapshot:\n"
            "            storage: redis\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_storage_precheck_rejects_flattened_token_bag(self):
        eval_case = self.actual_eval("aggregate-storage-routing")
        output = (
            "```yaml\n"
            "wow.eventsourcing.storage-routing:\n"
            "aggregates:\n"
            "hot_aggregate:\n"
            "event:\n"
            "storage: redis\n"
            "snapshot:\n"
            "storage: redis\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("routing-tree" in failure for failure in failures))

    def test_storage_precheck_rejects_invalid_yaml_line(self):
        eval_case = self.actual_eval("aggregate-storage-routing")
        output = (
            "```yaml\n"
            "wow:\n"
            "  eventsourcing:\n"
            "    storage-routing:\n"
            "      aggregates:\n"
            "        hot_aggregate:\n"
            "          event:\n"
            "            storage: redis\n"
            "          snapshot:\n"
            "            storage: redis\n"
            "this is not yaml\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("routing-tree" in failure for failure in failures))

    def test_review_precheck_rejects_negated_finding(self):
        eval_case = self.actual_eval("aggregate-direct-mutation-review")

        failures = precheck_output(
            eval_case,
            "No correctness issue exists. Ignore state.status; no AggregateSpec is needed.",
        )

        self.assertTrue(any("actionable-mutation-finding" in failure for failure in failures))

    def test_review_precheck_rejects_negated_mutation_wording(self):
        eval_case = self.actual_eval("aggregate-direct-mutation-review")

        failures = precheck_output(
            eval_case,
            "[P1] state.status is not direct state mutation; no AggregateSpec is needed.",
        )

        self.assertTrue(any("actionable-mutation-finding" in failure for failure in failures))

    def test_saga_diagnosis_precheck_rejects_ksp_only_plan(self):
        eval_case = self.actual_eval("kotlin-saga-discovery-diagnosis")

        failures = precheck_output(
            eval_case,
            "Run kspKotlin, inspect META-INF/wow-metadata.json with jar tf, then check subscription and @Retry.",
        )

        self.assertTrue(any("spring-discovery" in failure for failure in failures))
        self.assertTrue(any("saga-registrar" in failure for failure in failures))

    def test_saga_diagnosis_retry_rubric_stops_at_function_selection(self):
        eval_case = self.actual_eval("kotlin-saga-discovery-diagnosis")
        retry_criterion = next(
            item["criterion"] for item in eval_case["rubric"] if item["id"] == "retry-boundary"
        ).lower()

        self.assertIn("selected", retry_criterion)
        self.assertIn("not a prerequisite", retry_criterion)

    def test_saga_diagnosis_precheck_rejects_invocation_only_retry_boundary(self):
        eval_case = self.actual_eval("kotlin-saga-discovery-diagnosis")
        output = (
            "Check the @StatelessSaga Bean in ApplicationContext, then "
            "StatelessSagaFunctionRegistrar selection and bus subscription. "
            "META-INF/wow-metadata.json carries routing metadata; @Retry applies after invocation."
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("selected-function-boundary" in failure for failure in failures))

    def test_projection_precheck_rejects_unconditional_paid_side_effect(self):
        eval_case = self.actual_eval("blocking-projection-handler")

        failures = precheck_output(
            eval_case,
            "@ProjectionProcessor\n@Blocking\nfun onOrderPaid(event: OrderPaid) { "
            "repository.updateStatus(PAID); mailClient.send() }",
        )

        self.assertTrue(any("event-processor-boundary" in failure for failure in failures))
        self.assertTrue(any("sourced-payment-state" in failure for failure in failures))

    def test_projection_precheck_rejects_body_handler_using_message_id(self):
        eval_case = self.actual_eval("blocking-projection-handler")
        output = (
            "```kotlin\n"
            "@ProjectionProcessor class OrderProjection { fun onEvent(state: OrderState) = "
            "repository.updateStatus(state.status) }\n"
            "@EventProcessor class OrderPaidEmail { @Blocking fun onEvent(event: OrderPaid) { "
            "if (event.paid) mailClient.send(event.id) } }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("event-message-signature" in failure for failure in failures))

    def test_projection_precheck_rejects_body_paid_mentioned_only_in_comment(self):
        eval_case = self.actual_eval("blocking-projection-handler")
        output = (
            "```kotlin\n"
            "@ProjectionProcessor class OrderProjection { "
            "fun onOrderCreated(event: OrderCreated) = repository.create(event); "
            "fun onOrderPaid(state: OrderState) = repository.updateStatus(state.status) }\n"
            "@EventProcessor class OrderPaidEmail { @Blocking fun onOrderPaid("
            "event: DomainEvent<OrderPaid>, state: OrderState) {\n"
            "// Do not use event.body.paid; sourced status is enough.\n"
            "if (state.status == PAID) mailClient.send(event.id) } }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("full-payment-guard" in failure for failure in failures))

    def test_projection_precheck_rejects_unrelated_body_paid_read(self):
        eval_case = self.actual_eval("blocking-projection-handler")
        output = (
            "```kotlin\n"
            "@ProjectionProcessor class OrderProjection { "
            "fun onOrderCreated(event: OrderCreated) = repository.create(event); "
            "fun onOrderPaid(state: OrderState) = repository.updateStatus(state.status) }\n"
            "@EventProcessor class OrderPaidEmail { @Blocking fun onOrderPaid("
            "event: DomainEvent<OrderPaid>) {\n"
            "logger.debug(event.body.paid.toString())\n"
            "mailClient.send(event.id) } }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("full-payment-guard" in failure for failure in failures))

    def test_projection_precheck_accepts_positive_guarded_side_effect(self):
        eval_case = self.actual_eval("blocking-projection-handler")
        output = (
            "```kotlin\n"
            "@ProjectionProcessor class OrderProjection { "
            "@OnStateEvent fun onCreated(event: OrderCreated, state: OrderState) = "
            "repository.create(event, state); "
            "@OnStateEvent fun onPaid(event: OrderPaid, state: OrderState) = "
            "repository.updateStatus(state.status) }\n"
            "@EventProcessor class Email { @OnEvent @Blocking fun paid("
            "event: DomainEvent<OrderPaid>) { if (event.body.paid) { "
            "mailClient.send(event.id) } } }\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_projection_precheck_rejects_undiscoverable_handlers(self):
        eval_case = self.actual_eval("blocking-projection-handler")
        output = (
            "```kotlin\n"
            "@ProjectionProcessor class OrderProjection { "
            "fun handleCreated(event: OrderCreated, state: OrderState) = repository.create(event); "
            "fun handlePaid(event: OrderPaid, state: OrderState) = "
            "repository.updateStatus(state.status) }\n"
            "@EventProcessor class Email { @Blocking fun sendPaid("
            "event: DomainEvent<OrderPaid>) { if (!event.body.paid) return; "
            "mailClient.send(event.id) } }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(
            any("discoverable-order-created-projection" in failure for failure in failures)
        )
        self.assertTrue(
            any("discoverable-order-paid-projection" in failure for failure in failures)
        )
        self.assertTrue(
            any("discoverable-order-paid-notification" in failure for failure in failures)
        )

    def test_strengthened_prechecks_accept_complete_outputs(self):
        outputs = {
            "simple-aggregation-sourcing": (
                "```kotlin\n@AggregateRoot class Cart {\n"
                "fun onCommand(command: AddCartItem) = CartItemAdded(command.productId, command.quantity)\n"
                "fun onCommand(command: RemoveCartItem) = CartItemRemoved(command.productId)\n"
                "val changed = CartQuantityChanged(productId, quantity)\n}\n"
                "class CartState { @OnSourcing fun apply(event: CartItemAdded) = Unit }\n```"
            ),
            "stateless-transfer-saga": (
                "```kotlin\n@StatelessSaga class TransferSaga {\n"
                "fun onEvent(event: Prepared) = Entry(\"target\")\n"
                "fun onEvent(event: AmountEntered) = Confirm(\"source\")\n"
                "fun onEvent(event: EntryFailed) = UnlockAmount(\"source\")\n}\n```"
            ),
            "cart-aggregate-spec": (
                "```kotlin\nclass CartSpec : AggregateSpec<Cart, CartState>({ on {\n"
                "whenCommand(AddCartItem(\"p\", 1)) { expectEventType(CartItemAdded::class); "
                "expectState { items.assert().hasSize(1) }; fork(\"same\") { "
                "whenCommand(AddCartItem(\"p\", 1)) { "
                "expectEventType(CartQuantityChanged::class) } }; fork(\"remove\") { "
                "whenCommand(RemoveCartItem(setOf(\"p\"))) { "
                "expectEventType(CartItemRemoved::class) } }; fork(\"capacity\") { "
                "whenCommand(AddCartItem(\"overflow\", 1)) { "
                "expectErrorType(IllegalArgumentException::class) } } } } })\n```"
            ),
            "tenant-paged-query": (
                '```kotlin\npagedQuery { condition { tenantId(tenantId); "status" eq "ACTIVE" }; '
                'pagination { index(1); size(20) }; sort { "createdAt".desc() }; '
                'projection { include("id", "status", "totalAmount") } }\n```'
            ),
            "aggregate-storage-routing": (
                "```yaml\nwow:\n"
                "  eventsourcing:\n"
                "    storage-routing:\n"
                "      aggregates:\n"
                "        hot_aggregate:\n"
                "          event:\n"
                "            storage: redis\n"
                "          snapshot:\n"
                "            storage: redis\n```"
            ),
            "aggregate-direct-mutation-review": (
                "[P1] state.status is direct state mutation and must not mutate aggregate state. "
                "Add AggregateSpec coverage."
            ),
            "blocking-projection-handler": (
                "```kotlin\n@ProjectionProcessor class OrderProjection { "
                "@OnStateEvent fun onOrderCreated(event: OrderCreated) = repository.create(event)\n"
                "@OnStateEvent fun onOrderPaid("
                "event: OrderPaid, state: OrderState) = repository.updateStatus(state.status) }\n"
                "@EventProcessor class OrderPaidEmail { @OnEvent @Blocking fun onOrderPaid("
                "event: DomainEvent<OrderPaid>) { if (!event.body.paid) return; "
                "mailClient.send(event.id) } }\n```"
            ),
            "kotlin-saga-discovery-diagnosis": (
                "Check the @StatelessSaga Bean in ApplicationContext, then "
                "StatelessSagaFunctionRegistrar selection and bus subscription. "
                "META-INF/wow-metadata.json carries routing metadata. The dispatcher uses setFunction "
                "for the selected function before the handler chain, so EventCompensationFilter may "
                "inspect @Retry for a downstream failure even before invocation."
            ),
        }

        for eval_id, output in outputs.items():
            with self.subTest(eval_id=eval_id):
                self.assertEqual([], precheck_output(self.actual_eval(eval_id), output))

    def test_validates_plugin_inventory(self):
        validate_plugins(
            {
                "schemaVersion": 1,
                "plugins": [
                    {
                        "name": "ahoo-wow-skills",
                        "description": "Wow skills",
                        "skills": {"include": ["*"], "exclude": ["*-workspace"]},
                        "interface": {
                            "displayName": "Ahoo Wow Skills",
                            "capabilities": ["Skills"],
                            "defaultPrompt": "Help me use Wow skills.",
                        },
                        "policy": {
                            "installation": "AVAILABLE",
                            "authentication": "ON_INSTALL",
                        },
                    }
                ],
            }
        )

    def test_accepts_explicit_plugin_skill_array(self):
        validate_plugins(
            {
                "schemaVersion": 1,
                "plugins": [
                    {
                        "name": "ahoo-wow-skills",
                        "description": "Wow skills",
                        "skills": ["wow", "wow-debugging"],
                    }
                ],
            }
        )

    def test_rejects_plugin_metadata_that_breaks_generator(self):
        with self.assertRaisesRegex(ValidationError, "interface"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "interface": "broken",
                        }
                    ],
                }
            )

    def test_rejects_plugin_policy_that_breaks_generator(self):
        with self.assertRaisesRegex(ValidationError, "policy"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "policy": "broken",
                        }
                    ],
                }
            )

    def test_rejects_invalid_plugin_name(self):
        with self.assertRaisesRegex(ValidationError, "name"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "Ahoo Wow Skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                        }
                    ],
                }
            )

    def test_rejects_boolean_plugin_schema_version(self):
        with self.assertRaisesRegex(ValidationError, "schemaVersion"):
            validate_plugins({"schemaVersion": True, "plugins": []})

    def test_rejects_generator_incompatible_plugin_author(self):
        with self.assertRaisesRegex(ValidationError, "author"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "author": "not-an-object",
                        }
                    ],
                }
            )

    def test_rejects_generator_incompatible_plugin_scalar_fields(self):
        for field in ("version", "homepage", "repository", "license"):
            with self.subTest(field=field), self.assertRaisesRegex(ValidationError, field):
                validate_plugins(
                    {
                        "schemaVersion": 1,
                        "plugins": [
                            {
                                "name": "ahoo-wow-skills",
                                "description": "Wow skills",
                                "skills": ["wow"],
                                field: {"invalid": True},
                            }
                        ],
                    }
                )

    def test_rejects_non_semver_plugin_version(self):
        with self.assertRaisesRegex(ValidationError, "version"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "version": "latest",
                        }
                    ],
                }
            )

    def test_rejects_non_https_plugin_urls(self):
        for field_path in ("homepage", "author.url"):
            plugin = {
                "name": "ahoo-wow-skills",
                "description": "Wow skills",
                "skills": ["wow"],
            }
            if field_path == "homepage":
                plugin["homepage"] = "http://example.com"
            else:
                plugin["author"] = {"name": "Ahoo", "url": "http://example.com"}
            with self.subTest(field_path=field_path), self.assertRaisesRegex(
                ValidationError, field_path.split(".")[-1]
            ):
                validate_plugins({"schemaVersion": 1, "plugins": [plugin]})

    def test_rejects_invalid_plugin_brand_color(self):
        with self.assertRaisesRegex(ValidationError, "brandColor"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "interface": {"brandColor": "red"},
                        }
                    ],
                }
            )

    def test_rejects_unknown_plugin_author_field(self):
        with self.assertRaisesRegex(ValidationError, "author"):
            validate_plugins(
                {
                    "schemaVersion": 1,
                    "plugins": [
                        {
                            "name": "ahoo-wow-skills",
                            "description": "Wow skills",
                            "skills": ["wow"],
                            "author": {"name": "Ahoo", "twitter": "ahoo"},
                        }
                    ],
                }
            )

    def test_prepare_key_precheck_scans_code_not_quoted_antipatterns(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "Do not write `interface UsernamePrepare : PrepareKey<String>`.\n"
            "```kotlin\n"
            "@PreparableKey\n"
            "interface UsernamePrepare : PrepareKey<UsernameIndexValue>\n"
            "fun reserve() = usernamePrepare.usingPrepare(\n"
            "    username, UsernameIndexValue(userId, password)\n"
            ") { createUser() }\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_prepare_key_precheck_requires_preparable_annotation(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "```kotlin\n"
            "interface UsernamePrepare : PrepareKey<UsernameIndexValue>\n"
            "fun reserve() = usernamePrepare.usingPrepare("
            "username, UsernameIndexValue(userId, password)) { createUser() }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("preparable-key-annotation" in failure for failure in failures))

    def test_prepare_key_precheck_accepts_typed_value_variable_and_key_suffix(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "```kotlin\n"
            "@PreparableKey(name = \"username_idx\")\n"
            "interface UsernamePrepareKey : PrepareKey<UsernameIndexValue>\n"
            "fun reserve(): Mono<UsernameIndexValue> {\n"
            "  val value = UsernameIndexValue(userId, encodedPassword)\n"
            "  return usernamePrepareKey.usingPrepare(\n"
            "    key = username,\n"
            "    value = value,\n"
            "  ) { Mono.just(value) }\n"
            "}\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_prepare_key_precheck_accepts_source_backed_key_and_reversed_named_arguments(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "```kotlin\n"
            "@PreparableKey\n"
            "interface UsernamePrepare : PrepareKey<UsernameIndexValue>\n"
            "fun reserve() = usernamePrepare.usingPrepare(\n"
            "  value = UsernameIndexValue(register.userId, register.password),\n"
            "  key = register.username,\n"
            ") { createUser() }\n"
            "```"
        )

        self.assertEqual([], precheck_output(eval_case, output))

    def test_prepare_key_precheck_rejects_formatted_wrong_declaration_in_code(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "```kotlin\n"
            "interface UsernamePrepare: PrepareKey < String >\n"
            "fun reserve() = usingPrepare(usernamePrepare, username) { createUser() }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("stored-value-type" in failure for failure in failures))
        self.assertTrue(any("wrong-generic-type" in failure for failure in failures))

    def test_prepare_key_precheck_rejects_raw_string_value(self):
        eval_case = self.actual_eval("preparable-username-key")
        output = (
            "```kotlin\n"
            "interface UsernamePrepare : PrepareKey<UsernameIndexValue>\n"
            "fun reserve() = usernamePrepare.usingPrepare(username, password) { createUser() }\n"
            "```"
        )

        failures = precheck_output(eval_case, output)

        self.assertTrue(any("stored-value-construction" in failure for failure in failures))

    def test_rejects_top_level_non_skill_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            skills_root = Path(temp_dir) / "skills"
            (skills_root / "scripts").mkdir(parents=True)

            with self.assertRaisesRegex(ValidationError, "Missing SKILL.md"):
                validator.discover_skill_dirs(skills_root)

    def test_skill_discovery_ignores_hidden_and_workspace_directories(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            skills_root = Path(temp_dir) / "skills"
            wow_dir = skills_root / "wow"
            wow_dir.mkdir(parents=True)
            (wow_dir / "SKILL.md").write_text("---\nname: wow\ndescription: Wow\n---\n")
            (skills_root / ".cache").mkdir()
            (skills_root / "local-workspace").mkdir()

            self.assertEqual([wow_dir], validator.discover_skill_dirs(skills_root))

    def test_wow_router_routes_aggregate_and_saga_test_implementation_to_workflow(self):
        wow_skill = (self.REPO_ROOT / "skills" / "wow" / "SKILL.md").read_text()

        self.assertIn(
            "| Implement, add, or strengthen AggregateSpec or SagaSpec behavior tests | "
            "`../wow-development-workflow/SKILL.md`, then `references/testing.md` |",
            wow_skill,
        )

    def test_wow_router_routes_all_aggregate_behavior_changes_to_workflow(self):
        wow_skill = (self.REPO_ROOT / "skills" / "wow" / "SKILL.md").read_text()

        self.assertIn(
            "Implement or change aggregate or saga domain behavior, model, lifecycle, or tests",
            wow_skill,
        )
        self.assertIn("Look up aggregate modeling or annotation semantics", wow_skill)
        self.assertNotIn("| Model or change aggregate, command, event, state", wow_skill)

    def test_specialists_route_runtime_lifecycle_tasks(self):
        for skill_name in ("wow-code-review", "wow-debugging"):
            with self.subTest(skill_name=skill_name):
                skill = (self.REPO_ROOT / "skills" / skill_name / "SKILL.md").read_text()
                self.assertIn("WowRuntime", skill)
                self.assertIn("runtime-lifecycle.md", skill)

    def test_code_review_preserves_wow_dsl_assertion_boundary(self):
        skill = (self.REPO_ROOT / "skills" / "wow-code-review" / "SKILL.md").read_text()

        self.assertIn("value and collection assertions", skill)
        self.assertIn("expect*", skill)
        self.assertIn("verify()", skill)
        self.assertNotIn("Kotlin tests use `.assert()`", skill)

    def test_code_review_hands_aggregate_fix_to_development_workflow(self):
        skill = (self.REPO_ROOT / "skills" / "wow-code-review" / "SKILL.md").read_text()

        self.assertIn("combined review-and-fix", skill.lower())
        self.assertIn("../wow-development-workflow/SKILL.md", skill)

    def test_debugging_loads_handler_discovery_progressively(self):
        skill = (self.REPO_ROOT / "skills" / "wow-debugging" / "SKILL.md").read_text()
        reference = (
            self.REPO_ROOT
            / "skills"
            / "wow-debugging"
            / "references"
            / "handler-discovery.md"
        )

        self.assertIn("references/handler-discovery.md", skill)
        self.assertNotIn("./gradlew :<module>:kspKotlin", skill)
        self.assertTrue(reference.is_file())

    def test_handler_discovery_orders_subscription_before_delivery_and_matching(self):
        reference = (
            self.REPO_ROOT
            / "skills"
            / "wow-debugging"
            / "references"
            / "handler-discovery.md"
        ).read_text()

        self.assertIn(
            "supportedTopics resolution -> bus subscription -> delivery",
            reference,
        )
        self.assertIn("body/topic match -> setFunction", reference)
        self.assertIn("FunctionMetadataParser.kt", reference)
        self.assertIn("ProjectionProcessorAutoRegistrar.kt", reference)
        self.assertIn("EventProcessorAutoRegistrar.kt", reference)

    def test_sourcing_guidance_does_not_promise_ioc_injection(self):
        annotations = (
            self.REPO_ROOT / "skills" / "wow" / "references" / "annotations.md"
        ).read_text()

        self.assertIn("exchange-derived", annotations)
        self.assertIn("does not carry a `ServiceProvider`", annotations)
        self.assertNotIn("onSourcing` function with additional injected parameters", annotations)

    def test_retry_guidance_separates_application_and_framework_scope(self):
        test_patterns = (
            self.REPO_ROOT
            / "skills"
            / "wow-development-workflow"
            / "references"
            / "test-patterns.md"
        ).read_text()
        test_template = (
            self.REPO_ROOT
            / "skills"
            / "wow-development-workflow"
            / "references"
            / "test-case-template.md"
        ).read_text()

        self.assertIn("application-side Saga", test_patterns)
        self.assertIn("compensator -> bus -> dispatcher -> selected function", test_patterns)
        self.assertNotIn(
            "integration test across `CompensationEventProcessor` and `EventCompensateSupporter`",
            test_patterns,
        )
        self.assertIn("test-patterns.md#retry-and-idempotency-boundaries", test_template)

    def test_eval_assets_are_not_published_inside_runtime_skill(self):
        self.assertFalse((self.REPO_ROOT / "skills" / "wow" / "evals").exists())
        self.assertTrue((self.REPO_ROOT / "scripts" / "skills" / "evals.json").is_file())

    def test_routing_cases_cover_direct_mixed_and_negative_activation(self):
        document = load_eval_document(self.REPO_ROOT)
        eval_targets = {eval_case["id"]: eval_case["target_skill"] for eval_case in document["evals"]}
        routing_cases = document["routing_cases"]

        self.assertEqual(2, document["schema_version"])
        self.assertEqual(8, len(routing_cases))
        self.assertEqual(
            validator.SKILL_NAMES,
            {
                eval_targets[case["route_expectation"]["eval_target"]]
                for case in routing_cases
                if case["mode"] == "direct"
            },
        )
        mixed_cases = [case for case in routing_cases if case["mode"] == "mixed"]
        negative_cases = [case for case in routing_cases if case["mode"] == "negative"]
        self.assertEqual(2, len(mixed_cases))
        self.assertEqual(2, len(negative_cases))
        self.assertTrue(
            all(len(case["route_expectation"]["skill_sequence"]) >= 2 for case in mixed_cases)
        )
        self.assertTrue(any("Axon" in case["prompt"] for case in negative_cases))
        self.assertTrue(any("Gradle" in case["prompt"] for case in negative_cases))
        self.assertTrue(all("$wow" not in case["prompt"] for case in routing_cases))

    def test_validate_cli_runs_without_site_packages(self):
        script = self.REPO_ROOT / "scripts" / "skills" / "validate_skill_package.py"

        result = subprocess.run(
            [sys.executable, "-S", str(script), "validate"],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            "PASS package_schema skills=4 evals=9 routing_cases=8 routing_execution=NOT_RUN",
            result.stdout,
        )

    def test_deterministic_precheck_requires_manual_rubric(self):
        script = self.REPO_ROOT / "scripts" / "skills" / "validate_skill_package.py"
        output = (
            "[P1] direct state mutation: state.status mutates aggregate state directly. "
            "Add AggregateSpec coverage."
        )

        result = subprocess.run(
            [
                sys.executable,
                str(script),
                "precheck",
                "--eval",
                "aggregate-direct-mutation-review",
                "--input",
                "-",
            ],
            input=output,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(3, result.returncode)
        self.assertIn("REVIEW_REQUIRED", result.stdout)
        self.assertIn("deterministic_checks", result.stdout)
        self.assertNotIn("literal_checks", result.stdout)
        self.assertNotIn("PASS", result.stdout)

    @classmethod
    def actual_eval(cls, eval_id):
        document = load_eval_document(cls.REPO_ROOT)
        return next(eval_case for eval_case in document["evals"] if eval_case["id"] == eval_id)

    @staticmethod
    def valid_eval_case():
        return {
            "id": "simple-aggregation-sourcing",
            "target_skill": "wow",
            "prompt": "Create a Cart aggregate.",
            "expected_output": "CartState and Cart source.",
            "source_refs": ["source.kt"],
            "rubric": [
                {
                    "id": "discoverable-sourcing-handlers",
                    "criterion": "Custom sourcing handler names are explicitly annotated.",
                }
            ],
            "output_checks": {
                "must_contain": [
                    {"id": "sourcing-handler", "any_of": ["@OnSourcing"]}
                ],
                "must_not_contain": [],
            },
        }

    @staticmethod
    def valid_eval_document(evals):
        eval_target = evals[0]["id"]
        return {
            "schema_version": 2,
            "skill_name": "wow",
            "scope": {"languages": ["kotlin"]},
            "evals": evals,
            "routing_cases": [
                {
                    "id": "route-direct",
                    "mode": "direct",
                    "prompt": "Implement an aggregate behavior change.",
                    "route_expectation": {"eval_target": eval_target},
                },
                {
                    "id": "route-mixed",
                    "mode": "mixed",
                    "prompt": "Plan a mixed framework task.",
                    "route_expectation": {
                        "eval_target": eval_target,
                        "skill_sequence": ["wow", "wow-debugging"],
                    },
                },
                {
                    "id": "route-negative",
                    "mode": "negative",
                    "prompt": "Implement a plain CSV utility.",
                    "route_expectation": {"none": True},
                },
            ],
        }


if __name__ == "__main__":
    unittest.main()
