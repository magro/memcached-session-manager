package de.javakaffee.web.msm.storage.redis;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DummyFuture implements Future<Boolean> {
	private Boolean result;

	public DummyFuture(Boolean result) {
		this.result = result;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCancelled() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isDone() {
		return true;
	}

	@Override
	public Boolean get() throws InterruptedException, ExecutionException {
		return result;
	}

	@Override
	public Boolean get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		return result;
	}

}
