"""Command line: list tools, call one tool, or run the agent. Only ``ask`` calls Claude."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import asdict
from pathlib import Path

import anyio

from gitsleuth.agent import Agent, AgentLimits, AgentResult, StopReason
from gitsleuth.dispatcher import ToolCall
from gitsleuth.logs import configure_logging
from gitsleuth.mcp_client import McpConnectError
from gitsleuth.model import DEFAULT_EFFORT, DEFAULT_MODEL, AnthropicModelClient
from gitsleuth.toolbox import open_toolbox, stdio_target


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="gitsleuth", description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="write JSON event logs to stderr")
    commands = parser.add_subparsers(dest="command", required=True)

    def add_command(name: str, help_text: str) -> argparse.ArgumentParser:
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--repo", default=".", help="git repository (default: current dir)")
        command.add_argument("--code-url", help="use a running code server over Streamable HTTP")
        command.add_argument(
            "--history-url", help="use a running history server over Streamable HTTP"
        )
        return command

    add_command("tools", "list the tools the MCP servers expose")

    call = add_command("call", "run one tool through the dispatcher, without a model")
    call.add_argument("tool", help="exposed name, such as code__read_file")
    call.add_argument("arguments", nargs="?", default="{}", help="JSON object of arguments")

    ask = add_command("ask", "run the agent on a question (makes billed Claude API calls)")
    ask.add_argument("question")
    defaults = AgentLimits()
    ask.add_argument("--model", default=DEFAULT_MODEL)
    ask.add_argument("--effort", default=DEFAULT_EFFORT)
    ask.add_argument("--max-steps", type=int, default=defaults.max_steps)
    ask.add_argument("--max-tool-calls", type=int, default=defaults.max_tool_calls)
    ask.add_argument("--max-total-tokens", type=int, default=defaults.max_total_tokens)
    ask.add_argument("--deadline", type=float, default=defaults.deadline_seconds)
    ask.add_argument("--json", action="store_true", help="print the whole result as JSON")
    return parser


def _print_result(result: AgentResult) -> None:
    print(result.answer or "(no answer)")
    if result.findings:
        print("\nFindings:")
        for finding in result.findings:
            commits = ", ".join(commit[:10] for commit in finding.commits)
            print(f"- [{finding.confidence}] {finding.claim} ({commits})")
    print(
        f"\nStopped: {result.stop_reason.value}"
        + (f" ({result.detail})" if result.detail else "")
        + f" | {result.steps} steps, {len(result.tool_calls)} tool calls,"
        + f" {result.usage.input_tokens} input and {result.usage.output_tokens} output tokens"
    )


async def _run(args: argparse.Namespace) -> int:
    code = args.code_url or stdio_target("code", args.repo)
    history = args.history_url or stdio_target("history", args.repo)

    async with open_toolbox(code, history) as toolbox:
        if args.command == "tools":
            for definition in toolbox.registry.definitions():
                print(f"{definition['name']}: {definition['description']}")
            return 0

        if args.command == "call":
            outcome = await toolbox.dispatcher.dispatch(
                ToolCall(id="cli", name=args.tool, arguments=json.loads(args.arguments))
            )
            print(outcome.text)
            return 1 if outcome.is_error else 0

        agent = Agent(
            model=AnthropicModelClient(model=args.model, effort=args.effort),
            dispatcher=toolbox.dispatcher,
            notebook=toolbox.notebook,
            limits=AgentLimits(
                max_steps=args.max_steps,
                max_tool_calls=args.max_tool_calls,
                max_total_tokens=args.max_total_tokens,
                deadline_seconds=args.deadline,
            ),
        )
        result = await agent.run(args.question)
        if args.json:
            print(json.dumps(asdict(result), indent=2, default=str))
        else:
            _print_result(result)
        return 1 if result.stop_reason is StopReason.MODEL_ERROR else 0


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    args.repo = str(Path(args.repo).resolve())
    if args.verbose:
        configure_logging(logging.INFO)
    try:
        sys.exit(anyio.run(_run, args))
    except McpConnectError as error:
        print(f"gitsleuth: {error}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError as error:
        print(f"gitsleuth: arguments are not valid JSON: {error}", file=sys.stderr)
        sys.exit(2)
