import http from 'k6/http';
import { check } from 'k6';

http.setResponseCallback(
  http.expectedStatuses(200, 429)
);

export const options = {
  vus: 100,
  duration: '30s',
};

export default function () {
  const res = http.get(
    'http://localhost:30080/products/1',
    {
      headers: {
        'X-Client-Id': 'bench',
      },
    }
  );

  check(res, {
    'valid rate-limit response': (r) =>
      r.status === 200 || r.status === 429,
  });
}