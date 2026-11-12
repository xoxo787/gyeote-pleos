import React from 'react';
import {Composition} from 'remotion';
import {Close, End, Film, FILM_LENGTH, Join, LEN, Play, Problem, Real, Rules, Title} from './scenes';

const size = {fps: 30, width: 1920, height: 1080};

/** `Film` is the whole video. The single scenes are registered too, for checking one at a time. */
export const Root: React.FC = () => (
  <>
    <Composition id="Film" component={Film} durationInFrames={FILM_LENGTH} {...size} />
    <Composition id="Title" component={Title} durationInFrames={LEN.title} {...size} />
    <Composition id="Problem" component={Problem} durationInFrames={LEN.problem} {...size} />
    <Composition id="Join" component={Join} durationInFrames={LEN.join} {...size} />
    <Composition id="Play" component={Play} durationInFrames={LEN.play} {...size} />
    <Composition id="Close" component={Close} durationInFrames={LEN.close} {...size} />
    <Composition id="Real" component={Real} durationInFrames={LEN.real} {...size} />
    <Composition id="Rules" component={Rules} durationInFrames={LEN.rules} {...size} />
    <Composition id="End" component={End} durationInFrames={LEN.end} {...size} />
  </>
);
