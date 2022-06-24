package io.github.albertus82.filestore;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DirectBlobExtractor implements BlobExtractor {

	@Override
	public InputStream getInputStream(final ResultSet rs, final int blobColumnIndex) throws SQLException {
		return rs.getBinaryStream(blobColumnIndex);
	}

}
