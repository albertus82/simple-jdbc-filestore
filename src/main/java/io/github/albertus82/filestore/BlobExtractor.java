package io.github.albertus82.filestore;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;

public interface BlobExtractor {

	InputStream getInputStream(ResultSet rs, int blobColumnIndex) throws SQLException;

}
