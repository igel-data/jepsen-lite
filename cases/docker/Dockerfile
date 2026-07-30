# A Linux box with lazyfs, for the :power-off nemesis.
#
# lazyfs is a FUSE filesystem, and FUSE means Linux -- so on a macOS or Windows
# host the power-off runs happen in here. Nothing else does: every other fault
# runs natively, and this image exists only because `fsync` can't be tested
# without a filesystem underneath the target that the test controls.
#
# Not store-specific. It carries what every suite needs (a JDK, the Clojure CLI,
# lazyfs) plus the native libraries the individual stores bind to.
#
# Build and run it with ../power-off.

FROM eclipse-temurin:21-jdk-noble

# - g++/cmake/make/git: to build lazyfs
# - libfuse3-*/fuse3:   what lazyfs is
# - liblmdb0:           lmdbjava binds to a system LMDB (see lmdb/README.md).
#                       sqlite-jdbc needs no equivalent -- it carries its own.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git rsync \
        g++ cmake make \
        fuse3 libfuse3-3 libfuse3-dev \
        liblmdb0 \
    && rm -rf /var/lib/apt/lists/*

# The Clojure CLI.
RUN curl -sL -o /tmp/install.sh \
        https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x /tmp/install.sh && /tmp/install.sh && rm /tmp/install.sh

# lazyfs 0.3.1 -- the version jepsen-lite documents itself as verified against.
# Two builds, in order: libpcache is what lazyfs's own build links against.
ARG LAZYFS_VERSION=0.3.1
RUN git clone --quiet https://github.com/dsrhaslab/lazyfs.git /opt/lazyfs \
    && cd /opt/lazyfs && git checkout --quiet ${LAZYFS_VERSION} \
    && cd libs/libpcache && ./build.sh \
    && cd ../../lazyfs && ./build.sh

# lazyfs mounts with allow_other so the target process can reach the mount.
RUN if grep -q '^#.*user_allow_other' /etc/fuse.conf 2>/dev/null; then \
        sed -i 's/^#\s*user_allow_other/user_allow_other/' /etc/fuse.conf; \
    else \
        echo user_allow_other >> /etc/fuse.conf; \
    fi

# Where lazyfs ended up: this is what a suite passes as :lazyfs {:dir ...}.
ENV JEPSEN_LITE_LAZYFS=/opt/lazyfs/lazyfs

WORKDIR /work
