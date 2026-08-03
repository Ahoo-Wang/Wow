"""Runner-owned behavioral oracles."""

from __future__ import annotations

import os
import subprocess  # nosec B404 - only fixed git commands are executed.
from pathlib import Path

from .model import EvalError, ORACLE_RUNTIME_DIR, UnsupportedEvidence

__all__ = [
    'require_cart_oracle_gradle_cache',
    'gradle_infrastructure_failure',
    'prepare_oracle_temp',
    'cart_capacity_oracle',
]


def require_cart_oracle_gradle_cache(workspace: Path) -> Path:
    gradle_home = workspace / ".eval-runtime/gradle-home"
    wrapper_distributions = list(
        gradle_home.glob("wrapper/dists/**/gradle-*/bin/gradle")
    )
    dependency_cache = gradle_home / "caches/modules-2/files-2.1"
    if not wrapper_distributions or not dependency_cache.is_dir():
        raise UnsupportedEvidence(
            "Cart oracle requires the workspace-local Gradle wrapper and dependency "
            "cache populated by the signed RED/GREEN commands"
        )
    return gradle_home


def gradle_infrastructure_failure(output: str) -> bool:
    normalized = output.lower()
    indicators = (
        "no cached version",
        "offline mode",
        "could not install gradle distribution",
        "unknownhostexception",
        "connectexception",
        "read timed out",
        "pkix path building failed",
        "no matching toolchains found",
        "toolchain download repositories have not been configured",
        "cannot find a java installation",
        "java_home is not set",
        "no 'java' command could be found",
    )
    return any(indicator in normalized for indicator in indicators)


def prepare_oracle_temp(oracle_runtime: Path) -> Path:
    if oracle_runtime.name != ORACLE_RUNTIME_DIR:
        raise EvalError("oracle runtime must use the runner-owned directory name")
    try:
        resolved_parent = oracle_runtime.parent.resolve()
        if oracle_runtime.is_symlink():
            raise EvalError("oracle runtime must not be a symlink")
        if oracle_runtime.exists() and not oracle_runtime.is_dir():
            raise EvalError("oracle runtime must be a directory")
        oracle_runtime.mkdir(exist_ok=True)
        resolved_runtime = oracle_runtime.resolve()
        if resolved_runtime.parent != resolved_parent:
            raise EvalError("oracle runtime escaped its runner-owned parent")
        temp_dir = oracle_runtime / "tmp"
        if temp_dir.is_symlink():
            raise EvalError("oracle temp directory must not be a symlink")
        if temp_dir.exists() and not temp_dir.is_dir():
            raise EvalError("oracle temp path must be a directory")
        temp_dir.mkdir(exist_ok=True)
        if temp_dir.resolve().parent != resolved_runtime:
            raise EvalError("oracle temp directory escaped the oracle runtime")
        return temp_dir
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot prepare runner-owned oracle runtime: {error}") from error


def cart_capacity_oracle(
    workspace: Path, expected_limit: int, oracle_runtime: Path
) -> bool:
    hidden_test = (
        workspace
        / "example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart"
        / "CartCapacityEvalOracleTest.kt"
    )
    gradlew = workspace / "gradlew"
    if hidden_test.exists() or not gradlew.is_file():
        return False
    hidden_test_source = f'''package me.ahoo.wow.example.domain.cart

import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CartCapacityEvalOracleTest {{
    private fun fullState(): CartState {{
        assertEquals({expected_limit}, MAX_CART_ITEM_SIZE)
        return CartState("eval-cart").also {{ state ->
            repeat(MAX_CART_ITEM_SIZE) {{ index ->
                state.onCartItemAdded(
                    CartItemAdded(CartItem(productId = "product-$index")),
                )
            }}
        }}
    }}

    @Test
    fun `existing product remains accepted at capacity`() {{
        val result = Cart(fullState()).onCommand(
            AddCartItem(productId = "product-0", quantity = 1),
        )
        val changed = assertInstanceOf(CartQuantityChanged::class.java, result)
        assertEquals(2, changed.changed.quantity)
    }}

    @Test
    fun `next distinct product is rejected at capacity`() {{
        assertThrows(IllegalArgumentException::class.java) {{
            Cart(fullState()).onCommand(
                AddCartItem(productId = "new-product", quantity = 1),
            )
        }}
    }}
}}
'''
    gradle_home = require_cart_oracle_gradle_cache(workspace)
    temp_dir = prepare_oracle_temp(oracle_runtime)
    environment = os.environ.copy()
    for key in (
        "BASH_ENV",
        "CI",
        "ENV",
        "GIT_ATTR_NOSYSTEM",
        "GIT_DIR",
        "GIT_DIFF_OPTS",
        "GIT_EXTERNAL_DIFF",
        "GIT_PAGER",
        "GIT_WORK_TREE",
        "GRADLE_OPTS",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "KOTLIN_OPTS",
        "PAGER",
    ):
        environment.pop(key, None)
    for key in list(environment):
        if key.startswith(("GIT_CONFIG_", "ORG_GRADLE_PROJECT_")):
            environment.pop(key)
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    environment["TMPDIR"] = str(temp_dir)
    try:
        hidden_test.write_text(hidden_test_source, encoding="utf-8")
        result = subprocess.run(  # nosec B603 - fixed runner-owned executable and argv.
            [
                str(gradlew),
                "--no-build-cache",
                "--no-configuration-cache",
                "--no-daemon",
                "--offline",
                "--rerun-tasks",
                ":example-domain:test",
                "--tests",
                "me.ahoo.wow.example.domain.cart.CartCapacityEvalOracleTest",
            ],
            cwd=workspace,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
            timeout=600,
        )
        if result.returncode != 0 and gradle_infrastructure_failure(
            f"{result.stdout}\n{result.stderr}"
        ):
            raise UnsupportedEvidence(
                "Cart oracle Gradle cache or Java toolchain is unavailable offline"
            )
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False
    finally:
        hidden_test.unlink(missing_ok=True)
