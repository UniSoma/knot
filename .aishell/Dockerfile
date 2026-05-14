FROM aishell:foundation

SHELL ["/bin/bash", "-eo", "pipefail", "-c"]

# Emacs 30.1 (nox build) + markdown-mode + package-lint for knot.el development.
# Bookworm only ships emacs 28.2; knot.el declares (emacs "30.1") in its
# Package-Requires header (see ticket kno-01krkhb71r1p), so emacs is built from
# the GNU tarball with --without-x. emacsclient is installed alongside emacs by
# `make install` and remains the capital-E escape hatch in knot.el's show buffer.
ARG EMACS_VERSION=30.1
RUN apt-get update; \
    apt-get install -y --no-install-recommends \
      build-essential \
      pkg-config \
      libgnutls28-dev \
      libncurses-dev \
      libgmp-dev \
      libjansson-dev \
      libxml2-dev \
      texinfo; \
    curl -fsSL "https://ftp.gnu.org/gnu/emacs/emacs-${EMACS_VERSION}.tar.xz" -o /tmp/emacs.tar.xz; \
    mkdir -p /tmp/emacs-src; \
    tar -xJf /tmp/emacs.tar.xz -C /tmp/emacs-src --strip-components=1; \
    cd /tmp/emacs-src; \
    ./configure \
      --without-x \
      --without-sound \
      --without-dbus \
      --without-mailutils \
      --with-modules; \
    make -j"$(nproc)"; \
    make install; \
    cd /; \
    rm -rf /tmp/emacs-src /tmp/emacs.tar.xz; \
    rm -rf /var/lib/apt/lists/*; \
    emacs --version

# markdown-mode (NonGNU ELPA, present in Emacs 30's default package-archives)
# and package-lint (MELPA) are installed into a system-wide elpa dir, with a
# site-start.el that (package-initialize)s at startup so `emacs --batch -L emacs/`
# picks them up without manual init — matching the previous Debian elpa-* behavior.
RUN mkdir -p /usr/local/share/emacs/site-lisp/elpa; \
    emacs --batch \
      --eval "(require 'package)" \
      --eval "(setq package-user-dir \"/usr/local/share/emacs/site-lisp/elpa\")" \
      --eval "(add-to-list 'package-archives '(\"melpa\" . \"https://melpa.org/packages/\") t)" \
      --eval "(package-initialize)" \
      --eval "(package-refresh-contents)" \
      --eval "(package-install 'markdown-mode)" \
      --eval "(package-install 'package-lint)"; \
    printf '%s\n' \
      "(require 'package)" \
      "(setq package-user-dir \"/usr/local/share/emacs/site-lisp/elpa\")" \
      "(package-initialize)" \
      > /usr/local/share/emacs/site-lisp/site-start.el

# JDK 25 from Eclipse Temurin (foundation only ships JRE 17 headless).
COPY --from=eclipse-temurin:25-jdk-jammy /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:${PATH}"

ARG CLOJURE_VERSION=1.12.4.1618
RUN curl -fsSL "https://github.com/clojure/brew-install/releases/download/${CLOJURE_VERSION}/linux-install.sh" -o /tmp/linux-install.sh; \
    bash /tmp/linux-install.sh; \
    rm /tmp/linux-install.sh; \
    clojure --version

ARG CLOJURE_LSP_VERSION=2025.06.13-20.45.44
RUN curl -fsSL "https://github.com/clojure-lsp/clojure-lsp/releases/download/${CLOJURE_LSP_VERSION}/clojure-lsp-native-static-linux-amd64.zip" -o /tmp/clojure-lsp.zip; \
    unzip /tmp/clojure-lsp.zip -d /usr/local/bin; \
    rm /tmp/clojure-lsp.zip; \
    clojure-lsp --version

ARG CLJ_KONDO_VERSION=2026.04.15
RUN curl -fsSL "https://github.com/clj-kondo/clj-kondo/releases/download/v${CLJ_KONDO_VERSION}/clj-kondo-${CLJ_KONDO_VERSION}-linux-static-amd64.zip" -o /tmp/clj-kondo.zip; \
    unzip /tmp/clj-kondo.zip -d /usr/local/bin; \
    rm /tmp/clj-kondo.zip; \
    clj-kondo --version

# Clojure MCP and MCP Light
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2; \
    bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'; \
    bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]' 

# clj-surgeon — structural analysis + AST refactoring CLI for Clojure.
ARG CLJ_SURGEON_SHA=a6c0da960a59bdeb7927e9a79234893b02413ace
RUN bbin install https://github.com/realgenekim/clj-surgeon.git --git/sha "${CLJ_SURGEON_SHA}"

