package io.github.albertus82.filestore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
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

public class SimpleJdbcFileStore implements SimpleFileStore {

	private static final Logger log = Logger.getLogger(SimpleJdbcFileStore.class.getName());

	private static final char escapeChar = '\\';

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
	public List<Resource> list(final String dir, final boolean recurse) throws IOException {
		final Path path = Path.forDirectory(dir);
		final String sql = "SELECT directory, filename, content_length, last_modified FROM " + sanitizeTableName(tableName) + " WHERE directory " + (recurse ? "LIKE ? ESCAPE '" + escapeChar + "'" : "= ?");
		log.fine(sql);
		try {
			return jdbcTemplate.query(sql, (rs, rowNum) -> new DatabaseResource(Path.forFile(rs.getString(1) + rs.getString(2)), rs.getLong(3), rs.getTimestamp(4).getTime()), recurse ? path.getDirectory().replace("_", escapeChar + "_").replace("%", escapeChar + "%") + '%' : path.getDirectory());
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public Resource get(final String path) throws IOException {
		Objects.requireNonNull(path, "path must not be null");
		return get(Path.forFile(path));
	}

	private Resource get(final Path path) throws IOException {
		final String sql = "SELECT content_length, last_modified FROM " + sanitizeTableName(tableName) + " WHERE directory = ? AND filename = ?";
		log.fine(sql);
		try {
			return jdbcTemplate.query(sql, rs -> {
				if (rs.next()) {
					return new DatabaseResource(path, rs.getLong(1), rs.getTimestamp(2).getTime());
				}
				else {
					throw new EmptyResultDataAccessException(1);
				}
			}, path.getDirectory(), path.getFileName());
		}
		catch (final EmptyResultDataAccessException e) {
			log.log(Level.FINE, e, () -> path.toString());
			throw new NoSuchFileException(path.toString());
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void write(final String path, final Resource resource) throws IOException {
		Objects.requireNonNull(resource, "resource must not be null");
		Objects.requireNonNull(path, "path must not be null");
		write(Path.forFile(path), resource);
	}

	private void write(final Path path, final Resource resource) throws IOException {
		final long contentLength = insert(path, resource);
		if (resource.isOpen()) {
			final String sql = "UPDATE " + sanitizeTableName(tableName) + " SET content_length = ? WHERE directory = ? AND filename = ?";
			log.fine(sql);
			try {
				jdbcTemplate.update(sql, contentLength, path.getDirectory(), path.getFileName());
			}
			catch (final DataAccessException e) {
				throw new IOException(e);
			}
		}
	}

	private long insert(final Path path, final Resource resource) throws IOException {
		final long contentLength = resource.isOpen() ? -1 : resource.contentLength();
		final String sql = "INSERT INTO " + sanitizeTableName(tableName) + " (directory, filename, content_length, last_modified, compressed, file_contents) VALUES (?, ?, ?, ?, ?, ?)";
		log.fine(sql);
		try (final InputStream ris = resource.getInputStream(); final CountingInputStream cis = new CountingInputStream(ris); final InputStream is = Compression.NONE.equals(compression) ? cis : new DeflaterInputStream(cis, new Deflater(getDeflaterLevel(compression)))) {
			jdbcTemplate.execute(sql, new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {
				@Override
				protected void setValues(final PreparedStatement ps, final LobCreator lobCreator) throws SQLException {
					ps.setString(1, path.getDirectory());
					ps.setString(2, path.getFileName());
					ps.setLong(3, contentLength);
					ps.setTimestamp(4, determineLastModifiedTimestamp(resource));
					ps.setBoolean(5, !Compression.NONE.equals(compression));
					lobCreator.setBlobAsBinaryStream(ps, 6, is, Compression.NONE.equals(compression) && contentLength < Integer.MAX_VALUE ? (int) contentLength : -1);
				}
			});
			return cis.getCount();
		}
		catch (final DuplicateKeyException e) {
			log.log(Level.FINE, e, () -> path.toString());
			throw new FileAlreadyExistsException(path.toString());
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void move(final String source, final String target) throws IOException {
		Objects.requireNonNull(source, "oldPath must not be null");
		Objects.requireNonNull(target, "newPath must not be null");
		move(Path.forFile(source), Path.forFile(target));
	}

	private void move(final Path source, final Path target) throws IOException, NoSuchFileException, FileAlreadyExistsException {
		final String sql = "UPDATE " + sanitizeTableName(tableName) + " SET directory = ?, filename = ? WHERE directory = ? AND filename = ?";
		log.fine(sql);
		try {
			if (jdbcTemplate.update(sql, target.getDirectory(), target.getFileName(), source.getDirectory(), source.getFileName()) == 0) {
				throw new NoSuchFileException(source.toString());
			}
		}
		catch (final DuplicateKeyException e) {
			throw new FileAlreadyExistsException(target.toString());
		}
		catch (final DataAccessException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void delete(final String path) throws IOException {
		Objects.requireNonNull(path, "path must not be null");
		delete(Path.forFile(path));
	}

	private void delete(final Path path) throws IOException, NoSuchFileException {
		final String sql = "DELETE FROM " + sanitizeTableName(tableName) + " WHERE directory = ? AND filename = ?";
		log.fine(sql);
		try {
			if (jdbcTemplate.update(sql, path.getDirectory(), path.getFileName()) == 0) {
				throw new NoSuchFileException(path.toString());
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

		private final Path path;
		private final long contentLength;
		private final long lastModified;

		private DatabaseResource(final Path path, final long contentLength, final long lastModified) {
			Objects.requireNonNull(path, "fileName must not be null");
			this.path = path;
			this.contentLength = contentLength;
			this.lastModified = lastModified;
		}

		@Override
		public String getFilename() {
			return path.getFileName();
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
				final String sql = "SELECT COUNT(*) FROM " + sanitizeTableName(tableName) + " WHERE directory = ? AND filename = ?";
				log.fine(sql);
				return jdbcTemplate.queryForObject(sql, boolean.class, path.getDirectory(), path.getFileName());
			}
			catch (final DataAccessException | IOException e) {
				log.log(Level.FINE, e, () -> "Could not retrieve data for existence check of " + getDescription());
				return false;
			}
		}

		@Override
		public String getDescription() {
			return "Database resource [" + path + "]";
		}

		@Override
		public InputStream getInputStream() throws IOException {
			final String sql = "SELECT compressed, file_contents FROM " + sanitizeTableName(tableName) + " WHERE directory = ? AND filename = ?";
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
				}, path.getDirectory(), path.getFileName());
			}
			catch (final EmptyResultDataAccessException e) {
				log.log(Level.FINE, e, () -> path.toString());
				throw new NoSuchFileException(path.toString());
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

	private static class Path {

		private static final char separatorChar = '/';
		private static final String separator = "" + separatorChar;

		private final String directory;
		private final String fileName;

		private Path(final String directory, final String fileName) {
			Objects.requireNonNull(directory, "directory must not be null");
			Objects.requireNonNull(fileName, "fileName must not be null");
			this.directory = directory.trim();
			this.fileName = fileName.trim();
		}

		private static Path forFile(String fullPath) {
			if (fullPath == null || fullPath.isBlank()) {
				fullPath = separator;
			}
			else {
				fullPath = fullPath.trim().replace(File.separatorChar, separatorChar).replace('\\', separatorChar);
				if (!fullPath.startsWith(separator)) {
					fullPath = separator + fullPath;
				}
			}
			String canonicalizedPath = Arrays.stream(fullPath.split(separator + "+")).reduce((a, b) -> a.trim() + separator + b.trim()).orElse(separator);
			if (fullPath.endsWith(separator)) {
				canonicalizedPath += separator;
			}
			final String directory = canonicalizedPath.substring(0, fullPath.lastIndexOf(separatorChar) + 1);
			final String fileName = canonicalizedPath.substring(fullPath.lastIndexOf(separatorChar) + 1);
			return new Path(directory, fileName);
		}

		private static Path forDirectory(String path) {
			if (path != null && !path.endsWith(separator)) {
				path = path.trim() + separator;
			}
			return forFile(path);
		}

		private String getDirectory() {
			return directory;
		}

		private String getFileName() {
			return fileName;
		}

		@Override
		public String toString() {
			return directory + fileName;
		}
	}

}
