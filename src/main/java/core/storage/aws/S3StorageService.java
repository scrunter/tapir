package core.storage.aws;

import core.exceptions.StorageException;
import core.storage.StorageService;
import core.storage.util.StorageUtil;
import core.terraform.Module;
import core.terraform.Provider;
import core.upload.FormData;
import io.quarkus.arc.lookup.LookupIfProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@LookupIfProperty(name = "registry.storage.backend", stringValue = "s3")
@ApplicationScoped
public class S3StorageService extends StorageService {

  static final Logger LOGGER = Logger.getLogger(S3StorageService.class.getName());

  S3Client s3;
  S3AsyncClient s3AsyncClient;
  S3Presigner presigner;

  @ConfigProperty(name = "registry.storage.s3.bucket.name")
  String bucketName;


  public S3StorageService(S3Client s3, S3AsyncClient s3AsyncClient, S3Presigner presigner) {
    this.s3 = s3;
    this.s3AsyncClient = s3AsyncClient;
    this.presigner = presigner;
  }

  @Override
  public String getDownloadUrlForArtifact(String path) throws StorageException {
    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(path)
            .build();
    GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
            .getObjectRequest(getObjectRequest)
            .signatureDuration(Duration.ofMinutes(getAccessSessionDuration()))
            .build();
    try {
      PresignedGetObjectRequest presignedGetObjectRequest = presigner
              .presignGetObject(getObjectPresignRequest);
      return presignedGetObjectRequest.url().toString();
    } catch (Exception ex) {
      LOGGER.severe("Error occurred when generating pre-signed url");
      throw new StorageException(
              String.format("Could not create download url for module %s", path),
              ex
      );
    }
  }

  @Override
  public void uploadModule(FormData archive) throws StorageException {
    try {
      s3.putObject(buildModulePutRequest(archive),
              RequestBody.fromFile(archive.getPayload()));
    } catch (Exception ex) {
      throw new StorageException(((Module) archive.getEntity()).getId(), ex);
    }
  }

  protected PutObjectRequest buildModulePutRequest(FormData archive) {
    Module module = archive.getEntity();
    String s3ObjectKey = StorageUtil.generateModuleStoragePath(module);
    return PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3ObjectKey)
            .contentType(archive.getMimeType())
            .build();
  }

  @Override
  public void uploadProvider(FormData archive, String version) throws StorageException {
    Provider provider = archive.getEntity();
    Path s3PrefixPath = Path.of(
            StorageUtil.generateProviderStorageDirectory(provider, version)
    );
    try (Stream<Path> stream = Files.walk(archive.getCompressedFile().getParentFile().toPath())) {
      stream.filter(
              path -> path.toFile().isFile()
                      && !path.toString().endsWith(".tmp"))
              .forEach(pathToFile -> {
                buildProviderPutRequest(s3PrefixPath, pathToFile);
              });
    } catch (Exception ex) {
      throw new StorageException(((Provider) archive.getEntity()).getId(), ex);
    }
  }

  protected CompletableFuture<PutObjectResponse> buildProviderPutRequest(
          Path s3PrefixPath, Path pathToFile) {
    String s3Key = new StringBuilder(s3PrefixPath.toString())
            .append("/")
            .append(pathToFile.getFileName())
            .toString();
    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build();
    return s3AsyncClient.putObject(putObjectRequest, pathToFile);
  }
}
