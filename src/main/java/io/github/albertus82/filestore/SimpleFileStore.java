package io.github.albertus82.filestore;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.Resource;

public interface SimpleFileStore {

	List<Resource> list(String dir, boolean recurse) throws IOException;

	Resource get(String path) throws IOException;

	void write(String path, Resource resource) throws IOException;

	void move(String source, String target) throws IOException;

	void delete(String path) throws IOException;

}
