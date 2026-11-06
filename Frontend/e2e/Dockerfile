# The journeys' browser: Playwright's own image has the browsers, but not its Python package.
FROM mcr.microsoft.com/playwright/python:v1.63.0-noble
RUN pip install --no-cache-dir --break-system-packages playwright==1.63.0
# axe-core, which audits every screen: fetched once, here, and held to its checksum - not by each
# page from a CDN while the journeys run.
ADD --checksum=sha256:b511cd9dec01c76f4b2ad1723b66b6db37d4c2eb4ed199076e1829d9ee7b75e3 \
  https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js /opt/axe/axe.min.js
