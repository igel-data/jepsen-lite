# The demo HTTP KVS, as a container: the target for the `:compose` demo.
#
# Nothing about Jepsen Lite is in this image. It is the store, the way the
# database you actually wanted to test is the store -- Lite brings the service
# up from outside and tells Pumba which container to hit.
#
# Build context is the repository root (see docker-compose.yml).
FROM clojure:temurin-21-tools-deps

WORKDIR /app

# Dependencies first, so a code change doesn't re-fetch them.
COPY deps.edn .
RUN clojure -P -M:serve

COPY src src
COPY examples examples

# The store keeps its data on a volume, so a killed container comes back to
# what it had committed. Without that, a crash test would be checking that an
# empty store is empty.
VOLUME /data
EXPOSE 8080

# host=0.0.0.0, not the default loopback: a published port maps to the
# container's own interface, and a server listening on the container's loopback
# is reachable only from inside it.
CMD ["clojure", "-M:serve", "port=8080", "host=0.0.0.0", "dir=/data"]
