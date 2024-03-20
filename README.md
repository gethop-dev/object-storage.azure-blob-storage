[![ci-cd](https://github.com/gethop-dev/object-storage.azure-blob-storage/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/gethop-dev/object-storage.azure-blob-storage/actions/workflows/ci-cd.yml)
[![Clojars Project](https://img.shields.io/clojars/v/dev.gethop/object-storage.azure-blob-storage.svg)](https://clojars.org/dev.gethop/object-storage.azure-blob-storage)

# Object Storage - Azure Blob Storage

An Azure Blob Storage implementation of the [ObjectStorage][]
protocol. It provides optional [Integrant][] keys.

## Table of contents
* [Installation](#installation)
* [Usage](#usage)
  * [Put Object](#put-object)
  * [Copy Object](#copy-object)
  * [Get Object](#get-object)
  * [Get Object URL](#get-object-url)
  * [List Objects](#list-objects)
  * [Delete Object](#delete-object)
* [Testing](#testing)
* [License](#license)

## Installation

[![Clojars Project](https://clojars.org/dev.gethop/object-storage.azure-blob-storage/latest-version.svg)](https://clojars.org/dev.gethop/object-storage.azure-blob-storage)

## Usage

### Getting an `AzureBlobStorage` record

#### Using Integrant

The library provides a single [Integrant][] key,
`:dev.gethop.object-storage/azure-blob-storage`, that returns a
`AzureBlobStorage` record that can be used to perform
[ObjectStorage][] operations on a given Azure Blob Storage Container.

The key initialization expects the following keys:

* `:account`: A map with the following keys:
  * `connection-string`: The [Access Key][] Connection String of the
    account where you want to perform object operations.
* `:container`: A map with the following keys:
  * `name`: The name of the Azure Blob Storage Container where you
    want to perform object operations.
* `:presigned-url-lifespan`: Lifespan for presigned URLs. It is
  specified in minutes, and the default values is one hour.

Example configuration, with a presigned URL life span of 30 minutes:

``` edn
 :dev.gethop.object-storage/azure-blob-storage {:account {:connection-string "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=WoSTHF3LrMqVyWwOhkkxnZqsxCsneZjkqsJdmE7CsJk;EndpointSuffix=core.windows.net"}
                                                :container {:name "example"}
                                                :presigned-url-lifespan 30}
```

#### Non Integrant projects

The library can also be used without [Integrant][]. Just call the
`dev.gethop.object-storage.azure-blob-storage/init-record` function
with the same options you would use to initialize the Integrant
key.

``` clojure
(require '[dev.gethop.object-storage.azure-blob-storage :as abs])
(abs/init-record {:account {:connection-string "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=WoSTHF3LrMqVyWwOhkkxnZqsxCsneZjkqsJdmE7CsJk;EndpointSuffix=core.windows.net"}
                  :container {:name "example"}
                  :presigned-url-lifespan 30})
```

### Performing Azure Blob storage object operations

Require the `dev.gethop.object-storage.core` namespace to get the
`ObjectStorage` protocol definition.

``` clj
user> (require '[dev.gethop.object-storage.core :as object-storage])
nil
```

Then, initialize the Integrant key to get the Azure Blob Storage
boundary `AzureBlobStorage` record:

```clj
user> (def config {:account {:connection-string "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=WoSTHF3LrMqVyWwOhkkxnZqsxCsneZjkqsJdmE7CsJk;EndpointSuffix=core.windows.net"}
                  :container {:name "example"}
                  :presigned-url-lifespan 30})
#'user/config
user> (require '[dev.gethop.object-storage.azure-blob-storage]
               '[integrant.core :as ig])
nil
user> (def record (ig/init-key :dev.gethop.object-storage/azure-blob-storage config))
#'user/object-storage-record
```

 Once we have the protocol in place, we can use the `AzureBlobStorage`
 record to perform the following operations.

#### `put-object`

##### `(put-object object-storage-record object-id object)`

* description: Uploads an object to Azure Blob Storage with
  `object-id` as its Blob name.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the Blob to upload.
  - `object`: The file to upload (as a `java.io.File`-compatible value).
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:error-details`: a map with additional details on the problem
    encountered while trying to upload the object.

An example:

```clj
user> (require '[clojure.java.io :as io])
user> (object-storage/put-object record "object-id" (io/file "some-existing-file"))
{:success? true}
```

##### `(put-object object-storage-record object-id object opts)`

* description: Uploads an object to Azure Blob Storage with
  `object-id` as its Blob name.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the Blob  to upload.
  - `object`: The file to upload. It can be either a
    `java.io.File`-compatible value or an
    `java.io.InputStream`-compatible value. In the latter case, if you
    know the size of the content in the InputStream, add the
    `:metadata` key to the `opts` map.
* return value: a map with the following keys:
- `opts: A map of options. Currently supported option keys are:
    - `metadata`: It is a map with the following supported keys:
      - `:object-size`: The size, in bytes, of the `object` passed in as an InputStream.
  - `:success?`: boolean stating if the operation was successful or not.
  - `:error-details`: a map with additional details on the problem
    encountered while trying to upload the object.

An example:

```clj
user> (let [object-content (.getBytes "Test")
            object-size (count object-content)]
        (object-storage/put-object record
                                   "object-id"
                                   (io/input-stream object-content)
                                   {:metadata {:object-size object-size}}))
{:success? true}
```

#### `copy-object`

##### `(copy-object object-storage-record source-object-id destination-object-id)`

* description: Copies an object from AzureBlobStorage into the same Container.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `source-object-id`: The name/path of the Blob to copy.
  - `destination-object-id`: The destination Blob path/name to copy the source to.
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:error-details`: a map with additional details on the problem encountered while trying to copy the object.

Example:

```clj
user> (object-storage/copy-object record "some-existing-source-object-id" "new-destination-object-id")
{:success? true}
```

Failed operation when trying to copy a Blob that doesn't exist:

``` clojure
user> (object-storage/copy-object record "non-existing-object-id" "object-id")
{:success? false,
 :error-details
 {:body
  "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>BlobNotFound</Code><Message>The specified blob does not exist.\nRequestId:11ae648f-001e-0067-22b1-08f49e000000\nTime:2023-10-27T08:40:58.9785865Z</Message></Error>",
  :headers
  {:content-length "215",
   :content-type "application/xml",
   :date "Fri, 27 Oct 2023 08:40:58 GMT",
   :server "Windows-Azure-Blob/1.0 Microsoft-HTTPAPI/2.0",
   :x-ms-error-code "BlobNotFound",
   :x-ms-request-id "11ae648f-001e-0067-22b1-08f49e000000",
   :x-ms-version "2019-02-02"},
  :status 404}}

```

#### `get-object`

##### `(get-object object-storage-record object-id)`

* description: Retrieves an object from Azure Blob Storage.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the Blob to retrieve.
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:object`: If the operation was successful, this key contains an
    `InputStream`-compatible stream, on the desired object.
  - `:error-details`: a map with additional details on the problem encountered while trying to retrieve the object.

Example:

``` clojure
user> (object-storage/get-object record "object-id" "example.txt")
{:success? true,
 :object
 #object[org.httpkit.BytesInputStream 0x1e0830ba "BytesInputStream[len=4]"]}
```

Example when target object doesn't exist:

``` clojure
user> (object-storage/get-object record "non-existing-object-id" "example.txt")
{:success? false,
 :error-details
 {:body
  "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>BlobNotFound</Code><Message>The specified blob does not exist.\nRequestId:11af9480-001e-0067-48b1-08f49e000000\nTime:2023-10-27T08:42:42.4744107Z</Message></Error>",
  :headers
  {:content-length "215",
   :content-type "application/xml",
   :date "Fri, 27 Oct 2023 08:42:41 GMT",
   :server "Windows-Azure-Blob/1.0 Microsoft-HTTPAPI/2.0",
   :x-ms-error-code "BlobNotFound",
   :x-ms-request-id "11af9480-001e-0067-48b1-08f49e000000",
   :x-ms-version "2019-02-02"},
  :status 404}}
```

#### `get-object-url`

##### `(get-object-url object-storage-record object-id)`

* description: Gets a presigned URL that can be used to get the
  specified object without authentication. The URL lifespan is
  specified in the `AzureBlobStorage` record initialization.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the object in the Blob Container.
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:object-url`: If the operation was successful, this key contains
    a string with a presigned URL that can be used to get the
    specified object without authentication, but only within the
    configured lifespan. In addition, the presigned URL is only valid
    for GET requests.
  - `:error-details`: a map with additional details on the problem
    encountered while trying to create the presigned URL.

Example:

``` clojure
user> (object-storage/get-object-url record "object-id")
{:success? true,
 :object-url
 "https://gethop.blob.core.windows.net/object-storage-library-integration-tests/object-id?spr=https&sv=2020-12-06&sr=b&sp=r&se=2023-10-27T08%3A48%3A34Z&rscd=attachment&rsct=application%2Foctet-stream&sig=Jf6qM0oW%2BJpHe9%2FHErvcIhcAlxaP5o69xLfp3vr3p1Y%3D"}
```

##### `(get-object-url object-storage-record object-id opts)`

* description: Gets a presigned URL that can be used to access the
  specified object without authentication, using special options. The
  URL lifespan is specified in the `AzureBlobStorage` record
  initialization.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the object in the Blob Container.
  - `opts`: A map of options. Currently supported option keys are:
    - `:method`: Specifies the operation that we want to use with the presigned URL. It can be one of the following:
      - `:create`: Allows using a HTTP PUT request.
      - `:read`:  Allows using a HTTP GET request.
      - `:update`: Allows using a HTTP PUT request.
      - `:delete`: Allows using a HTTP DELETE request.
    - `:filename`: Specifies the filename that will be included in the
      "Content-Disposition" header for `:read` requests. It allows
      retrieving the object with a different name that the Blob
      name/path it was stored under.
    - `:content-type`: Specifies the value that will be included in
      the "Content-Type" header. Uses "application/octet-stream" as
      default if unspecified.
    - `:content-disposition`: Specifies the value that will be
      included in the "Content-Disposition" header. Has to be either
      `:inline` or `:attachment`. Defaults to `:attachment`.
    - `:object-public-url?`: A boolean that specifies if the URL returned by this function should be a publicly accessible one (if set to a truthy value) or a pre-signed URL (if set to a falsy value). If set to a truthy value, the object should be publicly accessible for the returned URL to work. This key is optional and, if not specified, defaults to a falsy value.<br>Due to Azure's lack of compatibility, if this key is set to a truthy value these other keys will be ignored:
      - `:filename`
      - `:content-type`
      - `:content-disposition`
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:object-url`: If the operation was successful, this key contains
    a string with a presigned URL that can be used to get the
    specified object without authentication, but only within the
    configured lifespan. In addition, the presigned URL is only valid
    for GET requests.
  - `:error-details`: a map with additional details on the problem
    encountered while trying to create the presigned URL.

Example of a presigned URL where a custom download file-name is specified:

``` clojure
user> (object-storage/get-object-url record "object-id" {:filename "other-arbitrary-filename"})
{:success? true,
 :object-url
 "https://gethop.blob.core.windows.net/object-storage-library-integration-tests/object-id?spr=https&sv=2020-12-06&sr=b&sp=r&se=2023-10-27T08%3A50%3A40Z&rscd=attachment%3B+filename%3Dother-arbitrary-filename&rsct=application%2Foctet-stream&sig=Skvc%2FTQrMbV59S8i6DWWFZCG4in3zf1I1GUiHKlqYb4%3D"}
```

#### `list-objects`

##### `(list-objects object-storage-record parent-object-id)`

* description: Gets a list of children objects from a given Blob storage path.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `parent-object-id`: The key of the object in the Azure Blob Storage to access.
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:objects`: If the operation was successful, this key contains a
    collection of maps.Each map represents a children object. Every
    object has 3 attributes: `object-id`, `last-modified` and `size`.
  - `:error-details`: a map with additional details on the problem
    encountered while trying retrieve the list of objects.

Example:

``` clojure
user> (object-storage/list-objects record nil)
{:success? true,
 :objects
 ({:object-id "object-id",
   :size 4,
   :last-modified
   #object[java.time.ZonedDateTime 0x233711e1 "2023-10-27T08:40:27Z"]})}
```

#### `delete-object`

##### `(delete-object object-storage-record object-id)`

* description: Deletes the object with key `object-id` from Azure Blob Storage.
* parameters:
  - `record`: An `AzureBlobStorage` record.
  - `object-id`: The name/path of the object in the Azure Blob Storage
    Container that we want to delete.
* return value: a map with the following keys:
  - `:success?`: boolean stating if the operation was successful or not.
  - `:error-details`: a map with additional details on the problem
    encountered while trying to retrieve the object.

Example:

``` clojure
user> (object-storage/delete-object record "object-id")
{:success? true}
```

Example with error when object-id doesn't exist:

``` clojure
user> (object-storage/delete-object record "non-existing-object-id")
{:success? false,
 :error-details
 {:body
  "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>BlobNotFound</Code><Message>The specified blob does not exist.\nRequestId:89177936-e01e-001d-4bb2-08e9de000000\nTime:2023-10-27T08:47:12.8146281Z</Message></Error>",
  :headers
  {:content-length "215",
   :content-type "application/xml",
   :date "Fri, 27 Oct 2023 08:47:12 GMT",
   :server "Windows-Azure-Blob/1.0 Microsoft-HTTPAPI/2.0",
   :x-ms-error-code "BlobNotFound",
   :x-ms-request-id "89177936-e01e-001d-4bb2-08e9de000000",
   :x-ms-version "2019-02-02"},
  :status 404}}
```

## Testing

The library includes self-contained units tests, including some
integration tests that depend on the Azure Blob Storage service. Those
tests have the `^:integration` metadata keyword associated to them, so
you can exclude them from our unit tests runs.

If you want to run the integration tests, the following set of
environment variables are needed:

* `TEST_AZURE_BLOB_STORAGE_ACCOUNT_CONNECTION_STRING`: The [Access
  Key][] connection string of the Azure Storage Account to be used
  when running the tests.
* `TEST_AZURE_BLOB_STORAGE_CONTAINER_NAME`: The name of the Container
  to be used when running the tests.

[Integrant]: https://github.com/weavejester/integrant
[ObjectStorage]: https://github.com/gethop-dev/object-storage.core
[Access Key]: https://go.microsoft.com/fwlink/?LinkId=2112507

## License

Copyright (c) 2023 Magnet S. Coop

The source code for the library is subject to the terms of the Mozilla
Public License, v. 2.0. If a copy of the MPL was not distributed with
this file, You can obtain one at https://mozilla.org/MPL/2.0/.
