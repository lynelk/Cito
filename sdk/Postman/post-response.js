pm.test('HTTP request was accepted (not proof of settlement or delivery)', () => {
  pm.expect(pm.response.code).to.be.within(200, 299);
});
if (pm.response.code === 202) {
  pm.test('202 remains pending, not completed money movement', () => pm.expect(pm.response.code).to.equal(202));
}
// Never automatically trigger a second request or change its reference/key after a failure.
