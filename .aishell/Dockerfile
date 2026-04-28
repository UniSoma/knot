# Copy JDK 25 from Eclipse Temurin official image
FROM eclipse-temurin:25-jdk-jammy AS jdk

# Build parinfer-rust from source
FROM rust:slim AS parinfer-builder
RUN apt-get update && apt-get install -y \
    git \
    libclang-dev \
    && git clone --depth 1 --branch v0.4.3 https://github.com/eraserhd/parinfer-rust.git /tmp/parinfer-rust \
    && cd /tmp/parinfer-rust \
    && cargo build --release \
    && strip /tmp/parinfer-rust/target/release/parinfer-rust

FROM aishell:base

# Use bash so we can do $(...) safely
SHELL ["/bin/bash", "-lc"]

# Install JDK 25 from Eclipse Temurin
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Copy parinfer-rust from builder stage
COPY --from=parinfer-builder /tmp/parinfer-rust/target/release/parinfer-rust /usr/local/bin/parinfer-rust

# Install Clojure tools-deps
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh \
    && rm linux-install.sh

# Install clj-kondo
RUN curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo \
    && chmod +x install-clj-kondo \
    && sudo ./install-clj-kondo \
    && rm install-clj-kondo

# clojure-lsp (native binary for fast startup)
ARG CLOJURE_LSP_VERSION=2025.11.28-12.47.43
RUN curl -fsSL https://github.com/clojure-lsp/clojure-lsp/releases/download/${CLOJURE_LSP_VERSION}/clojure-lsp-native-linux-amd64.zip \
    -o /tmp/clojure-lsp.zip \
    && unzip /tmp/clojure-lsp.zip -d /usr/local/bin \
    && chmod +x /usr/local/bin/clojure-lsp \
    && rm /tmp/clojure-lsp.zip

# bbin
ENV BABASHKA_BBIN_BIN_DIR=/usr/local/bin
RUN curl -fsSL https://raw.githubusercontent.com/babashka/bbin/main/bbin \
    -o /usr/local/bin/bbin \
    && chmod +x /usr/local/bin/bbin 

# Store gitlibs in shared location accessible to all users
ENV GITLIBS=/opt/gitlibs
ENV CLJ_CONFIG=/etc/clojure

# Pre-cache deps.clj tools in shared location accessible to all users
ENV DEPS_CLJ_TOOLS_DIR=/opt/clojure-tools
RUN bb clojure -Spath -Sdeps '{:deps {}}' \
    && chmod -R 755 /opt/clojure-tools

# Clojure MCP
RUN clojure -Ttools install-latest :lib io.github.bhauman/clojure-mcp :as mcp

# Clojure MCP Light
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.1 \
    && bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.1 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]' \
    && bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.1 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]' \
    && clj-nrepl-eval --help \
    && clj-paren-repair --help \
    && clj-paren-repair-claude-hook --help \
    && rm -f /tmp/bbin*

# clj-surgeon — structural analysis + AST refactoring CLI for Clojure
ARG CLJ_SURGEON_SHA=2eac91dcd893bc615cf68968788ffdedc0124779
RUN bbin install https://github.com/realgenekim/clj-surgeon.git --git/sha "${CLJ_SURGEON_SHA}" \
    && command -v clj-surgeon \
    && rm -f /tmp/bbin*

# Make Clojure config writable for runtime user (deps.clj writes tools.edn)
RUN chmod -R 777 /etc/clojure \
    && chmod -R 755 /opt/gitlibs

# Alias 'agilis' to 'bb agilis'
RUN echo "alias agilis='bb agilis'" >> /etc/bash.bashrc

# 'knot' shim: defers to `bb knot` in the current working directory's bb.edn,
# so editing src/ in a mounted project is picked up immediately (no install).
# A script on PATH (vs. a bash alias) means it also works in non-interactive
# shells — scripts, subagents, `sh -c`, IDE terminals.
RUN printf '%s\n' '#!/usr/bin/env bash' 'exec bb knot "$@"' > /usr/local/bin/knot \
    && chmod +x /usr/local/bin/knot
