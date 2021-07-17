all: lint test

.PHONY: lint
lint: 
	clojure -M:clj-1.10:async-1.0:lint

.PHONY: test
test: 
	clojure -M:clj-1.10:async-1.0:test