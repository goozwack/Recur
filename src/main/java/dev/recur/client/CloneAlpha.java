package dev.recur.client;

/** Added to entity render states: how visible a clone's body and name tag are (1 = normal, 0 = hidden). */
public interface CloneAlpha {
	float recur$getAlpha();
	void recur$setAlpha(float alpha);
	float recur$getTagAlpha();
	void recur$setTagAlpha(float alpha);
}
