ARG VARIANT="buster"
FROM mcr.microsoft.com/vscode/devcontainers/base:0-${VARIANT}

ARG CLOJURE_VERSION="1.10.3.855"

RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends default-jdk \
    && curl -O https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh
