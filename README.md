# Upload Service

A service to upload data to S3

## Performance Testing
https://github.com/Blackfynn/upload-perf

## How does uploading work?
1) Create a preview via the `/preview/organizations/{organizationId}` endpoint
  - The endpoint takes in a list of files each with integer uploadId
    which should essential just be the index of the element in the list.
  - For each file in the list a multipart upload in S3 is initiated.
  - For each file in a package preview the file is then cached in dynamo and 
    the package preview is cached in a separate dynamo table.
2) Insert chunks using the provided chunk size and should send the exact number
   of parts expected by the service as returned in the preview request.
   i) `/chunk/organizations/{organizationId}/id/{importId}`
    The above endpoint streams the whole chunk and will be able to handle upto
    a 5TB file. But it requires that the client send the SHA-256 hash of the
    chunk that will be uploaded as part of the request.
   ii) `/fineuploaderchunk/organizations/{organizationId}/id/{importId}`
    The above endpoint will write the chunk to be uploaded to disk and
    then stream it to S3. We have to do this as no hash is provided in this
    request. So we need to hash the entity before we can send the request.
    We as of writing hash the entity as we stream it to disk. This hash is then
    cached in dynamo to be used later for calculating a full hash of the uploaded
    file. We have placed a limit of 20MB chunk sizes on the endpoint. Which
    limits the total file size that can be uploaded to through this endpoint
    to 200GB. We should ONLY use this for the Front End Web App.
3) Complete the upload and start downstream processing (i.e. virus scanning)
   i) `/complete/organizations/{organizationId}/id/{importId}`
     The complete endpoint will complete the process of uploading a package.
     First it will load the preview of the package and all the associated file
     previews.
     For each file it will list all the chunks that have been uploaded to
     S3 and then use the listed chunks etags to complete the upload. After all
     files in the package preview have been completed it will contact API to
     start the process of creating the package and virus scanning.

**NB**
We provide additional endpoints to retrieve the hash for a file that was uploaded
and to get the current status of an uploaded. The status will inform the
consumer how many parts are left for files in the upload.

## Future Work
### Low Hanging Fruit
- Remove the multipart id from the preview file data and load it instead in the chunk
endpoints.
- Add completing an upload for an individual file and then add setting that as flag
on the dynamo cache. We can use that set flag to greatly simplify the process
of completing an upload. As we can bulk read from Dynamo far more efficiently and quickly than S3.
- Create separate CPU thread pool for doing complex nested preview generation.
Fix the hideous nested preview generation code.
### Higher Fruit
- Add the ability to accept a preview and use that to instantiate empty packages in API
This would greatly simplify the whole process as we can then allow people to upload
files in to a package and then trigger processing. Effectively partially decoupling
the process of uploading and creating a package.
- Remove dependency between Upload Service and API `files/upload/complete`
requires creating an endpoint that allows creating nested pacakges. And migrating
contacting JSS to Upload Service.
