# Lucene Indexer Search API

This project explores the capabilities of Apache Lucene, a powerful Java library for implementing full-text search features. The experiments in this repository demonstrate indexing, searching, and analyzing text data using Lucene's core APIs.

## Getting Started

To start this project, ensure you have Maven installed. Then, run the following command in your terminal:

```bash
mvn install
mvn exec:java
```

This will compile and execute the application on http://localhost:3000.

## Routes

### Import
  
The `/import` route allows you to upload and index a dataset of movies into the Lucene index. This endpoint expects a file (such as a CSV or JSON) containing movie data. Once the data is imported, it becomes searchable through the API.

**Example usage:**

```bash
curl -X POST -F 'file=@movies.json' http://localhost:3000/import
```

After a successful import, you will receive a confirmation message indicating the number of records indexed.

### Search
  
The `/search` route enables you to query the indexed movie data using various search parameters. You can provide keywords, filters, or other criteria to retrieve relevant movie records from the Lucene index.

**Example usage:**

```bash
curl "http://localhost:3000/search?q=final"
```

You can customize your search by adding query parameters such as `q` for keywords, or additional filters as supported by the API. The response will include a list of movies matching your search criteria, along with relevant details for each result.
