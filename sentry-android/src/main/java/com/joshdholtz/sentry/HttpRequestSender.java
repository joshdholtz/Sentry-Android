package com.joshdholtz.sentry;

public interface HttpRequestSender
{
	 interface Builder
	 {
		 Request build() throws Exception;

		 Builder useHttps();

		 Builder url(String url);

		 Builder header(String headerName, String headerValue);

		 Builder content(String requestData, String mediaType);
	 }

	interface Response
	{

		int getStatusCode();

		String getContent();
	}

	interface Request
	{
		Response execute() throws Exception;
	}


	Builder newBuilder();

}
