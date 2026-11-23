# Eval fixtures

All documents are synthetic and authored specifically for this retrieval evaluation. No external text or network source is required. Document keys are filenames without their extensions.

The two PDF fixtures are generated from the editable UTF-8 text files in `pdf-sources/` with macOS `cupsfilter`:

```bash
cupsfilter -m application/pdf src/test/resources/eval/pdf-sources/observability-handbook.txt \
  > src/test/resources/eval/docs/observability-handbook.pdf
cupsfilter -m application/pdf src/test/resources/eval/pdf-sources/billing-operations.txt \
  > src/test/resources/eval/docs/billing-operations.pdf
```

After regenerating either PDF, run `./gradlew test --tests '*EvalFixtureTest'` to verify extraction, expected phrases and multi-chunk relevance through the production parser.
