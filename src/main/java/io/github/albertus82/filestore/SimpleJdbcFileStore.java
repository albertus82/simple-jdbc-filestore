package io.github.albertus82.filestore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.InflaterInputStream;

import javax.sql.DataSource;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.jdbc.core.support.AbstractLobCreatingPreparedStatementCallback;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.jdbc.support.lob.LobCreator;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class SimpleJdbcFileStore implements SimpleFileStore {

	private static final Logger log = Logger.getLogger(SimpleJdbcFileStore.class.getName());

	private final JdbcTemplate jdbcTemplate;
	private final String tableName;
	private final Compression compression;
	private final BlobExtractor blobExtractor;

	public SimpleJdbcFileStore(final DataSource dataSource, final String tableName, final Compression compression, final BlobExtractor blobExtractor) {
		Objects.requireNonNull(dataSource, "dataSource must not be null");
		Objects.requireNonNull(tableName, "tableName must not be null");
		Objects.requireNonNull(compression, "compression must not be null");
		Objects.requireNonNull(blobExtractor, "blobExtractor must not be null");
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.tableName = tableName;
		this.compression = compression;
		this.blobExtractor = blobExtractor;
	}

	public String getTableName() {
		return tableName;
	}

	public Compression getCompression() {
		return compression;
	}

	public BlobExtractor getBlobExtractor() {
		return blobExtractor;
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public List<Resource> list() throws IOException {
		final String sql = "SELECT filename, content_length, last_modified FROM " + sanitizeTableName(tableName);
		log.fine(sql);
		try {
			return jdbcTemplate.query(sql, (rs, rowNum) -> new DatabaseResource(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).getTime()));
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Resource get(final String fileName) throws IOException {
		Objects.requireNonNull(fileName, "fileName must not be null");
		final String sql = "SELECT content_length, last_modified FROM " + sanitizeTableName(tableName) + " WHERE filename = ?";
		log.fine(sql);
		try {
			return jdbcTemplate.query(sql, rs -> {
				if (rs.next()) {
					return new DatabaseResource(fileName, rs.getLong(1), rs.getTimestamp(2).getTime());
				}
				else {
					throw new EmptyResultDataAccessException(1);
				}
			}, fileName);
		}
		catch (final EmptyResultDataAccessException e) {
			log.log(Level.FINE, e, () -> fileName);
			throw new NoSuchFileException(fileName);
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void store(final Resource resource, final String fileName) throws IOException {
		Objects.requireNonNull(resource, "resource must not be null");
		Objects.requireNonNull(fileName, "fileName must not be null");
		final long contentLength = insert(resource, fileName);
		if (resource.isOpen()) {
			final String sql = "UPDATE " + sanitizeTableName(tableName) + " SET content_length = ? WHERE filename = ?";
			log.fine(sql);
			try {
				jdbcTemplate.update(sql, contentLength, fileName);
			}
			catch (final DataAccessException e) {
				throw new IOException(e);
			}
		}
	}

	private long insert(final Resource resource, final String fileName) throws IOException {
		final long contentLength = resource.isOpen() ? -1 : resource.contentLength();
		final String sql = "INSERT INTO " + sanitizeTableName(tableName) + " (filename, content_length, last_modified, compressed, file_contents) VALUES (?, ?, ?, ?, ?)";
		log.fine(sql);
		try (final InputStream ris = resource.getInputStream(); final CountingInputStream cis = new CountingInputStream(ris); final InputStream is = Compression.NONE.equals(compression) ? cis : new DeflaterInputStream(cis, new Deflater(getDeflaterLevel(compression)))) {
			jdbcTemplate.execute(sql, new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {
				@Override
				protected void setValues(final PreparedStatement ps, final LobCreator lobCreator) throws SQLException {
					ps.setString(1, fileName);
					ps.setLong(2, contentLength);
					ps.setTimestamp(3, determineLastModifiedTimestamp(resource));
					ps.setBoolean(4, !Compression.NONE.equals(compression));
					lobCreator.setBlobAsBinaryStream(ps, 5, is, Compression.NONE.equals(compression) && contentLength < Integer.MAX_VALUE ? (int) contentLength : -1);
				}
			});
			return cis.getCount();
		}
		catch (final DuplicateKeyException e) {
			log.log(Level.FINE, e, () -> fileName);
			throw new FileAlreadyExistsException(fileName);
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void rename(final String oldFileName, final String newFileName) throws IOException {
		Objects.requireNonNull(oldFileName, "oldFileName must not be null");
		Objects.requireNonNull(newFileName, "newFileName must not be null");
		final String sql = "UPDATE " + sanitizeTableName(tableName) + " SET filename = ? WHERE filename = ?";
		log.fine(sql);
		try {
			if (jdbcTemplate.update(sql, newFileName, oldFileName) == 0) {
				throw new NoSuchFileException(oldFileName);
			}
		}
		catch (final DuplicateKeyException e) {
			throw new FileAlreadyExistsException(newFileName);
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void delete(final String fileName) throws IOException {
		Objects.requireNonNull(fileName, "fileName must not be null");
		final String sql = "DELETE FROM " + sanitizeTableName(tableName) + " WHERE filename = ?";
		log.fine(sql);
		try {
			if (jdbcTemplate.update(sql, fileName) == 0) {
				throw new NoSuchFileException(fileName);
			}
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	private String sanitizeTableName(final String tableName) throws IOException {
		try {
			return jdbcTemplate.execute(new StatementCallback<String>() {
				@Override
				public String doInStatement(final Statement stmt) throws SQLException {
					return stmt.enquoteIdentifier(tableName, false);
				}
			});
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	private static Timestamp determineLastModifiedTimestamp(final Resource resource) {
		try {
			final long lastModified = resource.lastModified();
			if (lastModified > 0) {
				return new Timestamp(lastModified);
			}
		}
		catch (final IOException e) {
			log.log(Level.FINE, e, resource::toString);
		}
		return new Timestamp(System.currentTimeMillis());
	}

	public class DatabaseResource extends AbstractResource { // NOSONAR Override the "equals" method in this class. Subclasses that add fields should override "equals" (java:S2160)

		private final String fileName;
		private final long contentLength;
		private final long lastModified;

		private DatabaseResource(final String fileName, final long contentLength, final long lastModified) {
			Objects.requireNonNull(fileName, "fileName must not be null");
			this.fileName = fileName;
			this.contentLength = contentLength;
			this.lastModified = lastModified;
		}

		@Override
		public String getFilename() {
			return fileName;
		}

		@Override
		public long contentLength() {
			return contentLength;
		}

		@Override
		public long lastModified() {
			return lastModified;
		}

		@Override
		public boolean exists() {
			try {
				final String sql = "SELECT COUNT(*) FROM " + sanitizeTableName(tableName) + " WHERE filename = ?";
				log.fine(sql);
				return jdbcTemplate.queryForObject(sql, boolean.class, fileName);
			}
			catch (final DataAccessException | IOException e) {
				log.log(Level.FINE, e, () -> "Could not retrieve data for existence check of " + getDescription());
				return false;
			}
		}

		@Override
		public String getDescription() {
			return "Database resource [" + fileName + "]";
		}

		@Override
		public InputStream getInputStream() throws IOException {
			final String sql = "SELECT compressed, file_contents FROM " + sanitizeTableName(tableName) + " WHERE filename = ?";
			log.fine(sql);
			try {
				return jdbcTemplate.query(sql, rs -> {
					if (rs.next()) {
						final boolean compressed = rs.getBoolean(1);
						final InputStream inputStream = blobExtractor.getInputStream(rs, 2);
						return compressed ? new InflaterInputStream(inputStream) : inputStream;
					}
					else {
						throw new EmptyResultDataAccessException(1);
					}
				}, fileName);
			}
			catch (final EmptyResultDataAccessException e) {
				log.log(Level.FINE, e, () -> fileName);
				throw new NoSuchFileException(fileName);
			}
			catch (final DataAccessException e) {
				throw new IOException(e);
			}
		}
	}

	private static int getDeflaterLevel(final Compression compression) {
		switch (compression) {
		case HIGH:
			return Deflater.BEST_COMPRESSION;
		case LOW:
			return Deflater.BEST_SPEED;
		case MEDIUM:
			return Deflater.DEFAULT_COMPRESSION;
		default:
			throw new IllegalArgumentException(compression.toString());
		}
	}

}
