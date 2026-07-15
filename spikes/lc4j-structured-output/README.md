# POC: LangChain4j structured output → Anthropic native json_schema

Proves LC4J 1.13.1 AI Services use Anthropic's native output_config
json_schema (server-enforced) when the model is built with
.supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA) — mandatory flag,
else silent prompt fallback.

Run: mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt &&
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -cp "target/classes:$(cat cp.txt)" poc.SchemaProbe
(ANTHROPIC_API_KEY from backend/.env or env; verdict = "json_schema" in logged request body)
