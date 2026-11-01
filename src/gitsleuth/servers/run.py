"""Command-line entry point for the MCP servers: ``gitsleuth-server code --repo PATH``."""

from __future__ import annotations

import argparse
import logging
import sys

from gitsleuth.servers.code_server import build_code_server
from gitsleuth.servers.git import GitRepo
from gitsleuth.servers.history_server import build_history_server

BUILDERS = {"code": build_code_server, "history": build_history_server}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="gitsleuth-server", description=__doc__)
    parser.add_argument("server", choices=sorted(BUILDERS))
    parser.add_argument("--repo", required=True, help="root of the git repository to expose")
    parser.add_argument("--transport", choices=["stdio", "streamable-http"], default="stdio")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8100)
    args = parser.parse_args(argv)

    # stdout carries the MCP protocol on stdio, so logs must go to stderr.
    logging.basicConfig(stream=sys.stderr, level=logging.WARNING)

    server = BUILDERS[args.server](GitRepo(args.repo))
    if args.transport == "stdio":
        server.run("stdio")
    else:
        server.run("streamable-http", host=args.host, port=args.port)


if __name__ == "__main__":
    main()
