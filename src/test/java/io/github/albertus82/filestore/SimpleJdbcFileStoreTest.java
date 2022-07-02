package io.github.albertus82.filestore;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import com.thedeanda.lorem.LoremIpsum;

@SpringJUnitConfig(TestConfig.class)
class SimpleJdbcFileStoreTest {

	private static final Logger log = Logger.getLogger(SimpleJdbcFileStoreTest.class.getName());

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void beforeAll() {
		Logger.getLogger("").setLevel(Level.FINE);
		for (final Handler h : Logger.getLogger("").getHandlers()) {
			h.setLevel(Level.FINE);
		}
	}

	@BeforeEach
	void beforeEach() {
		new ResourceDatabasePopulator(new ClassPathResource(getClass().getPackageName().replace('.', '/') + "/table.sql")).execute(jdbcTemplate.getDataSource());
	}

	@AfterEach
	void afterEach() {
		jdbcTemplate.execute("DROP TABLE storage");
	}

	@Test
	void testDatabase1() {
		jdbcTemplate.update("INSERT INTO storage (directory, filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?, ?)", "/1/", "a", 1, "x".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

	@Test
	void testDatabase2() {
		jdbcTemplate.update("INSERT INTO storage (directory, filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?, ?)", "/2/", "b", 2, "yz".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

	@Test
	void testApiBehaviour() throws IOException {
		final DataSource ds = jdbcTemplate.getDataSource();
		final FileBufferedBlobExtractor fbbe = new FileBufferedBlobExtractor();

		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, null, null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, null, null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, "STORAGE", null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, "STORAGE", Compression.MEDIUM, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, "STORAGE", null, fbbe));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, null, Compression.MEDIUM, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(ds, null, null, fbbe));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, "STORAGE", null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, null, Compression.MEDIUM, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, null, null, fbbe));
		Assertions.assertThrows(NullPointerException.class, () -> new FileBufferedBlobExtractor(null));

		final SimpleFileStore store = new SimpleJdbcFileStore(ds, "STORAGE", Compression.MEDIUM, fbbe);
		Assertions.assertDoesNotThrow(() -> store.list("/", true));
		Assertions.assertNotNull(store.list("/", true));
		Assertions.assertThrows(NullPointerException.class, () -> store.delete(null));
		Assertions.assertThrows(NullPointerException.class, () -> store.get(null));
		Assertions.assertThrows(NullPointerException.class, () -> store.move("a", null));
		Assertions.assertThrows(NullPointerException.class, () -> store.move(null, "b"));
		Assertions.assertThrows(NullPointerException.class, () -> store.move(null, null));

		final DescriptiveResource dr = new DescriptiveResource("x");
		Assertions.assertThrows(NullPointerException.class, () -> store.write("y", null));
		Assertions.assertThrows(NullPointerException.class, () -> store.write(null, dr));
		Assertions.assertThrows(NullPointerException.class, () -> store.write(null, null));
	}

	@Test
	void testList() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", Compression.MEDIUM, new FileBufferedBlobExtractor());
		Assertions.assertEquals(0, store.list("/", true).size());
		final Resource toSave = new InputStreamResource(getClass().getResourceAsStream("/10b.txt"));
		store.write("myfile.txt", toSave);
		Assertions.assertEquals(1, store.list("/", true).size());
	}

	@Test
	void testRename() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", Compression.MEDIUM, new FileBufferedBlobExtractor());
		final Resource toSave1 = new InputStreamResource(getClass().getResourceAsStream("/10b.txt"));
		store.write("foo.txt", toSave1);
		Assertions.assertTrue(store.get("foo.txt").exists());
		store.move("foo.txt", "bar.txt");
		Assertions.assertTrue(store.get("bar.txt").exists());
		Assertions.assertEquals(1, store.list("/", true).size());
		Assertions.assertThrows(NoSuchFileException.class, () -> store.get("foo.txt"));
		Assertions.assertThrows(NoSuchFileException.class, () -> store.move("foo.txt", "baz.txt"));
		final Resource toSave2 = new InputStreamResource(getClass().getResourceAsStream("/10b.txt"));
		store.write("foo.txt", toSave2);
		Assertions.assertEquals(2, store.list("/", true).size());
		Assertions.assertThrows(FileAlreadyExistsException.class, () -> store.move("foo.txt", "bar.txt"));
	}

	@Test
	void testStoreListGetDeleteFromStream() throws IOException {
		for (final BlobExtractor be : new BlobExtractor[] { new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor() }) {
			for (final Compression compression : Compression.values()) {
				final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", compression, be);
				try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
					store.write("myfile.txt", new InputStreamResource(is));
				}
				final long timeAfter = System.currentTimeMillis();
				final List<Resource> list = store.list("/", true);
				Assertions.assertEquals(1, list.size());
				final Resource r1 = list.get(0);
				Assertions.assertEquals("qwertyuiop".length(), r1.contentLength());
				Assertions.assertTrue(r1.exists());
				try (final InputStream is = r1.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals("myfile.txt", r1.getFilename());
					Assertions.assertTrue(timeAfter - r1.lastModified() < TimeUnit.SECONDS.toMillis(10));
				}
				final Resource r2 = store.get(r1.getFilename());
				Assertions.assertEquals(r1.contentLength(), r2.contentLength());
				Assertions.assertTrue(r2.exists());
				try (final InputStream is = r2.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals(r1.getFilename(), r2.getFilename());
					Assertions.assertEquals(r1.lastModified(), r2.lastModified());
				}
				store.delete("myfile.txt");
				Assertions.assertFalse(r2.exists());
				Assertions.assertThrows(NoSuchFileException.class, () -> r2.getInputStream());
				Assertions.assertThrows(NoSuchFileException.class, () -> store.get("myfile.txt"));
				Assertions.assertThrows(NoSuchFileException.class, () -> store.delete("myfile.txt"));
			}
		}
	}

	@Test
	void testStoreListGetDeleteFromFile() throws IOException {
		for (final BlobExtractor be : new BlobExtractor[] { new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor() }) {
			for (final Compression compression : Compression.values()) {
				final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", compression, be);
				Path tempFile = null;
				try {
					tempFile = Files.createTempFile(null, null);
					tempFile.toFile().deleteOnExit();
					Files.writeString(tempFile, "asdfghjkl");
					final BasicFileAttributes tempFileAttr = Files.readAttributes(tempFile, BasicFileAttributes.class);
					store.write("myfile.txt", new FileSystemResource(tempFile));
					final List<Resource> list = store.list("/", true);
					Assertions.assertEquals(1, list.size());
					final Resource r1 = list.get(0);
					Assertions.assertEquals("asdfghjkl".length(), r1.contentLength());
					Assertions.assertTrue(r1.exists());
					try (final InputStream is = r1.getInputStream()) {
						Assertions.assertArrayEquals("asdfghjkl".getBytes(), is.readAllBytes());
						Assertions.assertEquals("myfile.txt", r1.getFilename());
						Assertions.assertEquals(tempFileAttr.lastModifiedTime().toMillis(), r1.lastModified());
					}
					final Resource r2 = store.get(r1.getFilename());
					Assertions.assertEquals(r1.contentLength(), r2.contentLength());
					Assertions.assertTrue(r2.exists());
					try (final InputStream is = r2.getInputStream()) {
						Assertions.assertArrayEquals("asdfghjkl".getBytes(), is.readAllBytes());
						Assertions.assertEquals(r1.getFilename(), r2.getFilename());
						Assertions.assertEquals(r1.lastModified(), r2.lastModified());
					}
					store.delete("myfile.txt");
					Assertions.assertFalse(r2.exists());
					Assertions.assertThrows(NoSuchFileException.class, () -> r2.getInputStream());
					Assertions.assertThrows(NoSuchFileException.class, () -> store.get("myfile.txt"));
					Assertions.assertThrows(NoSuchFileException.class, () -> store.delete("myfile.txt"));
				}
				finally {
					Files.deleteIfExists(tempFile);
				}
			}
		}
	}

	@Test
	void testStore() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", Compression.LOW, new FileBufferedBlobExtractor());
		try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
			Assertions.assertDoesNotThrow(() -> store.write("myfile.txt", new InputStreamResource(is)));
		}
		Assertions.assertEquals(1, store.list("/", true).size());
	}

	@Test
	void testStoreLarge() throws Exception {
		Path tempFile = null;
		try {
			tempFile = createDummyFile(DataSize.ofMegabytes(32));
			final Path f = tempFile;
			List.of(new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor()).parallelStream().forEach(be -> {
				try {
					for (final Compression compression : Compression.values()) {
						final String fileName = UUID.randomUUID().toString();
						final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", compression, be);
						try (final InputStream is = Files.newInputStream(f)) {
							Assertions.assertDoesNotThrow(() -> store.write(fileName, new InputStreamResource(is)));
						}

						final byte[] buffer = new byte[8192];
						final MessageDigest digestSource = MessageDigest.getInstance("SHA-256");
						try (final InputStream is = Files.newInputStream(f)) {
							int bytesCount = 0;
							while ((bytesCount = is.read(buffer)) != -1) {
								digestSource.update(buffer, 0, bytesCount);
							}
						}
						final MessageDigest digestStored = MessageDigest.getInstance("SHA-256");
						try (final InputStream stored = store.get(fileName).getInputStream()) {
							int bytesCount = 0;
							while ((bytesCount = stored.read(buffer)) != -1) {
								digestStored.update(buffer, 0, bytesCount);
							}
						}
						Assertions.assertArrayEquals(digestSource.digest(), digestStored.digest());
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				catch (final NoSuchAlgorithmException e) {
					throw new RuntimeException(e);
				}
			});
		}
		finally {
			deleteIfExists(tempFile);
		}
	}

	@Test
	@Transactional
	void testStoreLargeTransactional() throws Exception {
		Path tempFile = null;
		try {
			tempFile = createDummyFile(DataSize.ofMegabytes(32));
			for (final Compression compression : Compression.values()) {
				final String fileName = UUID.randomUUID().toString();
				final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", compression, new DirectBlobExtractor());
				try (final InputStream is = Files.newInputStream(tempFile)) {
					Assertions.assertDoesNotThrow(() -> store.write(fileName, new InputStreamResource(is)));
				}

				final byte[] buffer = new byte[8192];
				final MessageDigest digestSource = MessageDigest.getInstance("SHA-256");
				try (final InputStream is = Files.newInputStream(tempFile)) {
					int bytesCount = 0;
					while ((bytesCount = is.read(buffer)) != -1) {
						digestSource.update(buffer, 0, bytesCount);
					}
				}
				final MessageDigest digestStored = MessageDigest.getInstance("SHA-256");
				try (final InputStream stored = store.get(fileName).getInputStream()) {
					int bytesCount = 0;
					while ((bytesCount = stored.read(buffer)) != -1) {
						digestStored.update(buffer, 0, bytesCount);
					}
				}
				Assertions.assertArrayEquals(digestSource.digest(), digestStored.digest());
			}
		}
		finally {
			deleteIfExists(tempFile);
		}
	}

	@Test
	void testDuplicate() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", Compression.HIGH, new FileBufferedBlobExtractor());
		try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
			store.write("myfile.txt", new InputStreamResource(is));
		}
		final List<Resource> list = store.list("/", true);
		Assertions.assertEquals(1, list.size());
		try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
			Assertions.assertThrows(FileAlreadyExistsException.class, () -> store.write("myfile.txt", new InputStreamResource(is)));
		}
	}

	@Test
	void testDirectories() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate.getDataSource(), "STORAGE", Compression.HIGH, new FileBufferedBlobExtractor());
		Assertions.assertEquals(0, store.list(" ", true).size());
		Assertions.assertEquals(0, store.list("", true).size());
		Assertions.assertEquals(0, store.list("/", true).size());
		Assertions.assertEquals(0, store.list(" / ", true).size());
		Assertions.assertEquals(0, store.list("\t /  ", true).size());

		Assertions.assertEquals(Collections.emptyList(), store.list("dir 1", false));
		try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
			store.write("dir 1/file1.txt", new InputStreamResource(is));
		}
		Assertions.assertEquals(1, store.list("dir 1", false).size());
		Assertions.assertEquals(1, store.list(" dir 1", false).size());
		Assertions.assertEquals(1, store.list(" dir 1 ", false).size());
		Assertions.assertEquals(1, store.list("  dir 1\t ", false).size());
		try (final InputStream is = getClass().getResourceAsStream("/10b.txt")) {
			store.write("dir 2/file1.txt", new InputStreamResource(is));
		}
		Assertions.assertEquals(1, store.list("dir 2", false).size());
		Assertions.assertEquals(2, store.list(" ", true).size());
		Assertions.assertEquals(2, store.list(null, true).size());
		Assertions.assertEquals(0, store.list(null, false).size());
	}

	private static Path createDummyFile(final DataSize size) throws IOException {
		if (size == null) {
			throw new NullPointerException();
		}
		if (size.toBytes() < 0) {
			throw new IllegalArgumentException(size.toString());
		}
		log.log(Level.FINE, "Creating {0} dummy file...", size);
		long currSize = 0;
		final Path tmp = Files.createTempFile(null, null);
		tmp.toFile().deleteOnExit();
		try (final Writer w = Files.newBufferedWriter(tmp, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING)) {
			while (currSize < size.toBytes()) {
				final String s = LoremIpsum.getInstance().getWords(100);
				currSize += s.length();
				w.append(s).append(System.lineSeparator());
			}
		}
		try (final RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw"); final FileChannel fc = raf.getChannel()) {
			fc.truncate(size.toBytes());
		}
		if (tmp.toFile().length() != size.toBytes()) {
			throw new IllegalStateException();
		}
		log.log(Level.FINE, "Dummy file created: \"{0}\".", tmp);
		return tmp;
	}

	private static void deleteIfExists(final Path file) {
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			}
			catch (final IOException e) {
				log.log(Level.FINE, e, () -> "Cannot delete file \"" + file + "\":");
				file.toFile().deleteOnExit();
			}
		}
	}

}
