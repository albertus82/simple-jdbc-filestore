package io.github.albertus82.filestore;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.Resource;

public interface SimpleFileStore {

	List<Resource> list() throws IOException;

	Resource get(String fileName) throws IOException;

	void store(Resource resource, String fileName) throws IOException;

	void rename(String oldFileName, String newFileName) throws IOException;

	void delete(String fileName) throws IOException;

}
